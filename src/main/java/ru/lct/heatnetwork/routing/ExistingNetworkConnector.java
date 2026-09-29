package ru.lct.heatnetwork.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.TypedIdOrder;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.network.AttachmentCandidates;
import ru.lct.heatnetwork.network.ExistingHeatChamber;
import ru.lct.heatnetwork.network.ExistingHeatNetwork;
import ru.lct.heatnetwork.projection.CrsTransform;
import org.springframework.stereotype.Component;

/** Projects proposed approach points onto existing pipes and resolves chamber use and capacity. */
@Component
public final class ExistingNetworkConnector {
    public static final double CHAMBER_SNAP_TOLERANCE_M = 0.25;
    public static final double EXISTING_CHAMBER_MAX_DISTANCE_M = 10.0;
    public static final int MAX_CHAMBER_CONNECTIONS = 4;
    private static final double ENDPOINT_EPSILON_M = 1e-9;
    private static final double DISTANCE_TIE_EPSILON_M = 1e-9;

    /**
     * Returns one nearest insertion location per existing line. If an available chamber on that line
     * is within 10 m of the selected insertion point, returns chamber endpoints instead. Callers may
     * invoke this for additional visibility-graph approach points; it is not a global route optimizer.
     */
    public AttachmentCandidates findCandidates(Point preferredLocation,
            Collection<ExistingHeatNetwork> networks, Collection<ExistingHeatChamber> chambers) {
        validatePoint(preferredLocation);
        if (networks == null || chambers == null) throw new IllegalArgumentException("Networks and chambers are required");

        List<ExistingHeatNetwork> orderedNetworks = new ArrayList<>(networks);
        Set<TypedId> networkIds = new HashSet<>();
        for (ExistingHeatNetwork network : orderedNetworks) {
            if (network == null || !networkIds.add(network.getId())) {
                throw new IllegalArgumentException("Existing network IDs must be unique and entries non-null");
            }
        }
        orderedNetworks.sort(Comparator.comparing(ExistingHeatNetwork::getId, TypedIdOrder.INSTANCE));

        List<ExistingHeatChamber> orderedChambers = new ArrayList<>(chambers);
        Set<TypedId> chamberIds = new HashSet<>();
        for (ExistingHeatChamber chamber : orderedChambers) {
            if (chamber == null || !chamberIds.add(chamber.getId())) {
                throw new IllegalArgumentException("Existing chamber IDs must be unique and entries non-null");
            }
        }
        orderedChambers.sort(Comparator.comparing(ExistingHeatChamber::getId, TypedIdOrder.INSTANCE));

        Map<TypedId, ChamberUsage> usageByChamber = new HashMap<>();
        for (ExistingHeatChamber chamber : orderedChambers) {
            ChamberUsage usage = chamberUsage(chamber, orderedNetworks);
            if (usage.connections > 0) usageByChamber.put(chamber.getId(), usage);
        }

        List<AttachmentCandidate> result = new ArrayList<>();
        for (ExistingHeatNetwork network : orderedNetworks) {
            LineString line = network.getGeometry();
            LengthIndexedLine indexedLine = new LengthIndexedLine(line);
            double measureM = indexedLine.project(preferredLocation.getCoordinate());
            Point insertion = line.getFactory().createPoint(indexedLine.extractPoint(measureM));
            insertion.setSRID(CrsTransform.PROJECTED_SRID);

            List<ChamberOption> available = new ArrayList<>();
            for (ExistingHeatChamber chamber : orderedChambers) {
                ChamberUsage usage = usageByChamber.get(chamber.getId());
                if (usage == null || usage.connections >= MAX_CHAMBER_CONNECTIONS
                        || !usage.networkIds.contains(network.getId())) continue;
                double distanceM = insertion.distance(chamber.getLocation());
                if (distanceM <= EXISTING_CHAMBER_MAX_DISTANCE_M) {
                    available.add(new ChamberOption(chamber, usage, distanceM));
                }
            }

            if (available.isEmpty()) {
                int existingLegs = lineLegsAt(line, measureM);
                result.add(new AttachmentCandidate(network.getId(), measureM, insertion, insertion,
                        AttachmentCandidate.EndpointKind.NEW_CHAMBER, null,
                        Collections.singletonList(network.getId()), existingLegs, network.getDiameterMm()));
            } else {
                orderChambersByDistance(available);
                for (ChamberOption option : available) {
                    result.add(new AttachmentCandidate(network.getId(), measureM, insertion,
                            option.chamber.getLocation(), AttachmentCandidate.EndpointKind.EXISTING_CHAMBER,
                            option.chamber.getId(), option.usage.networkIds, option.usage.connections,
                            option.usage.maximumDiameterMm));
                }
            }
        }
        return new AttachmentCandidates(result);
    }

    private static ChamberUsage chamberUsage(ExistingHeatChamber chamber,
            List<ExistingHeatNetwork> networks) {
        ChamberUsage usage = new ChamberUsage();
        Point location = chamber.getLocation();
        for (ExistingHeatNetwork network : networks) {
            LineString line = network.getGeometry();
            if (line.distance(location) <= CHAMBER_SNAP_TOLERANCE_M) {
                LengthIndexedLine indexedLine = new LengthIndexedLine(line);
                double measureM = indexedLine.project(location.getCoordinate());
                usage.connections += lineLegsAt(line, measureM);
                usage.maximumDiameterMm = Math.max(usage.maximumDiameterMm, network.getDiameterMm());
                usage.networkIds.add(network.getId());
            }
        }
        usage.networkIds.sort(TypedIdOrder.INSTANCE);
        return usage;
    }

    private static int lineLegsAt(LineString line, double measureM) {
        double lengthM = line.getLength();
        boolean atStart = measureM <= ENDPOINT_EPSILON_M;
        boolean atEnd = lengthM - measureM <= ENDPOINT_EPSILON_M;
        if (line.isClosed() && atStart) return 2;
        return atStart || atEnd ? 1 : 2;
    }

    private static void orderChambersByDistance(List<ChamberOption> options) {
        options.sort(Comparator.comparingDouble((ChamberOption option) -> option.distanceM)
                .thenComparing(option -> option.chamber.getId(), TypedIdOrder.INSTANCE));
        int start = 0;
        while (start < options.size()) {
            int end = start + 1;
            double nearest = options.get(start).distanceM;
            while (end < options.size() && options.get(end).distanceM - nearest <= DISTANCE_TIE_EPSILON_M) end++;
            if (end - start > 1) {
                options.subList(start, end).sort(Comparator.comparing(option -> option.chamber.getId(),
                        TypedIdOrder.INSTANCE));
            }
            start = end;
        }
    }

    private static void validatePoint(Point point) {
        if (point == null || point.isEmpty() || point.getSRID() != CrsTransform.PROJECTED_SRID
                || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY())) {
            throw new IllegalArgumentException("Preferred attachment location must be a finite EPSG:32637 point");
        }
    }

    private static final class ChamberUsage {
        private int connections;
        private int maximumDiameterMm;
        private final List<TypedId> networkIds = new ArrayList<>();
    }

    private static final class ChamberOption {
        private final ExistingHeatChamber chamber;
        private final ChamberUsage usage;
        private final double distanceM;
        private ChamberOption(ExistingHeatChamber chamber, ChamberUsage usage, double distanceM) {
            this.chamber = chamber;
            this.usage = usage;
            this.distanceM = distanceM;
        }
    }
}
