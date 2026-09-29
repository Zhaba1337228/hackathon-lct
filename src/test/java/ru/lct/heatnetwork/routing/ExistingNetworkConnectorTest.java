package ru.lct.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.network.AttachmentCandidates;
import ru.lct.heatnetwork.network.ExistingHeatChamber;
import ru.lct.heatnetwork.network.ExistingHeatNetwork;

class ExistingNetworkConnectorTest {
    private final ExistingNetworkConnector connector = new ExistingNetworkConnector();

    @Test
    void projectsApproachToLineAndCreatesChamberAtExactInsertionPoint() throws Exception {
        ExistingHeatNetwork network = network("main", "LINESTRING (0 0, 100 0)", 100);
        AttachmentCandidate candidate = only(connector.findCandidates(point("POINT (34 5)"),
                Collections.singletonList(network), Collections.emptyList()));

        assertEquals(34.0, candidate.getMeasureM(), 1e-9);
        assertPoint(34, 0, candidate.getInsertionPoint());
        assertPoint(34, 0, candidate.getEndpoint());
        assertEquals(AttachmentCandidate.EndpointKind.NEW_CHAMBER, candidate.getEndpointKind());
        assertEquals(2, candidate.getExistingConnectionCount());
        assertEquals(100, candidate.getMaximumExistingDiameterMm());
    }

    @Test
    void existingChamberAtExactlyTenMetresIsUsed() throws Exception {
        ExistingHeatNetwork network = network("main", "LINESTRING (0 0, 100 0)", 100);
        ExistingHeatChamber chamber = chamber("chamber", "POINT (20 0)");
        AttachmentCandidate candidate = only(connector.findCandidates(point("POINT (30 0)"),
                Collections.singletonList(network), Collections.singletonList(chamber)));

        assertEquals(AttachmentCandidate.EndpointKind.EXISTING_CHAMBER, candidate.getEndpointKind());
        assertEquals(chamber.getId(), candidate.getExistingChamberId());
        assertPoint(20, 0, candidate.getEndpoint());
        assertEquals(2, candidate.getExistingConnectionCount());
    }

    @Test
    void usesAvailableChamberAndTracksAllConnectedDiametersAndLegs() throws Exception {
        ExistingHeatNetwork main = network("main", "LINESTRING (0 0, 100 0)", 100);
        ExistingHeatNetwork branch = network("branch", "LINESTRING (50 0, 50 10)", 200);
        ExistingHeatChamber chamber = chamber("chamber", "POINT (50 0)");
        AttachmentCandidate candidate = forNetwork(connector.findCandidates(point("POINT (50 5)"),
                Arrays.asList(branch, main), Collections.singletonList(chamber)), "main",
                AttachmentCandidate.EndpointKind.EXISTING_CHAMBER);

        assertEquals(3, candidate.getExistingConnectionCount());
        assertEquals(200, candidate.getMaximumExistingDiameterMm());
        assertEquals(Arrays.asList(TypedId.of("branch"), TypedId.of("main")), candidate.getConnectedNetworkIds());
    }

    @Test
    void aThroughLineCountsTwoSlotsWhenTheChamberStillHasRoom() throws Exception {
        ExistingHeatNetwork main = network("main", "LINESTRING (0 0, 100 0)", 100);
        ExistingHeatNetwork branchA = network("branch-a", "LINESTRING (50 0, 50 10)", 65);
        ExistingHeatChamber chamber = chamber("chamber", "POINT (50 0)");
        AttachmentCandidates candidates = connector.findCandidates(point("POINT (50 0)"),
                Arrays.asList(main, branchA), Collections.singletonList(chamber));

        AttachmentCandidate accepted = forNetwork(candidates, "main",
                AttachmentCandidate.EndpointKind.EXISTING_CHAMBER);
        assertEquals(3, accepted.getExistingConnectionCount());
        assertEquals(chamber.getId(), accepted.getExistingChamberId());
    }

    @Test
    void fullChamberFallsBackToNewCameraAtSelectedPoint() throws Exception {
        ExistingHeatNetwork main = network("main", "LINESTRING (0 0, 100 0)", 100);
        ExistingHeatNetwork branchA = network("branch-a", "LINESTRING (50 0, 50 10)", 65);
        ExistingHeatNetwork branchB = network("branch-b", "LINESTRING (50 0, 50 -10)", 65);
        ExistingHeatChamber chamber = chamber("chamber", "POINT (50 0)");
        AttachmentCandidates candidates = connector.findCandidates(point("POINT (50 0)"),
                Arrays.asList(main, branchA, branchB), Collections.singletonList(chamber));

        AttachmentCandidate mainCandidate = forNetwork(candidates, "main", AttachmentCandidate.EndpointKind.NEW_CHAMBER);
        assertEquals(2, mainCandidate.getExistingConnectionCount());
        assertPoint(50, 0, mainCandidate.getEndpoint());
    }

    @Test
    void chamberMustSnapWithinQuarterMetreAndMustBeOnCandidateLine() throws Exception {
        ExistingHeatNetwork network = network("main", "LINESTRING (0 0, 100 0)", 100);
        ExistingHeatChamber onTolerance = chamber("on", "POINT (50 0.25)");
        ExistingHeatChamber outsideTolerance = chamber("outside", "POINT (50 0.251)");

        AttachmentCandidate accepted = only(connector.findCandidates(point("POINT (50 0)"),
                Collections.singletonList(network), Collections.singletonList(onTolerance)));
        AttachmentCandidate rejected = only(connector.findCandidates(point("POINT (50 0)"),
                Collections.singletonList(network), Collections.singletonList(outsideTolerance)));

        assertEquals(AttachmentCandidate.EndpointKind.EXISTING_CHAMBER, accepted.getEndpointKind());
        assertEquals(AttachmentCandidate.EndpointKind.NEW_CHAMBER, rejected.getEndpointKind());
    }

    @Test
    void equalDistanceChambersUseStableTypedNaturalIdOrder() throws Exception {
        ExistingHeatNetwork network = network("main", "LINESTRING (0 0, 100 0)", 100);
        List<ExistingHeatChamber> chambers = Arrays.asList(chamber("ch10", "POINT (45 0)"),
                chamber("ch2", "POINT (55 0)"), chamber(2, "POINT (45 0)"));
        AttachmentCandidates candidates = connector.findCandidates(point("POINT (50 0)"),
                Collections.singletonList(network), chambers);

        assertEquals(3, candidates.getCandidates().size());
        assertEquals(TypedId.of(new BigDecimal("2")), candidates.getCandidates().get(0).getExistingChamberId());
        assertEquals(TypedId.of("ch2"), candidates.getCandidates().get(1).getExistingChamberId());
        assertEquals(TypedId.of("ch10"), candidates.getCandidates().get(2).getExistingChamberId());
    }

    @Test
    void returnsNoCandidatesWithoutExistingNetworkAndRejectsNonProjectedInputs() throws Exception {
        assertTrue(connector.findCandidates(point("POINT (0 0)"), Collections.emptyList(),
                Collections.emptyList()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> connector.findCandidates(geographicPoint("POINT (0 0)"),
                Collections.emptyList(), Collections.emptyList()));
    }

    private static AttachmentCandidate only(AttachmentCandidates candidates) {
        assertEquals(1, candidates.getCandidates().size());
        return candidates.getCandidates().get(0);
    }

    private static AttachmentCandidate forNetwork(AttachmentCandidates candidates, String networkId,
            AttachmentCandidate.EndpointKind kind) {
        return candidates.getCandidates().stream()
                .filter(candidate -> candidate.getNetworkId().equals(TypedId.of(networkId))
                        && candidate.getEndpointKind() == kind)
                .findFirst().orElseThrow(() -> new AssertionError("No matching attachment candidate"));
    }

    private static ExistingHeatNetwork network(String id, String wkt, int diameter) throws Exception {
        return new ExistingHeatNetwork(TypedId.of(id), line(wkt), diameter);
    }

    private static ExistingHeatNetwork network(int id, String wkt, int diameter) throws Exception {
        return new ExistingHeatNetwork(TypedId.of(new BigDecimal(id)), line(wkt), diameter);
    }

    private static ExistingHeatChamber chamber(String id, String wkt) throws Exception {
        return new ExistingHeatChamber(TypedId.of(id), point(wkt));
    }

    private static ExistingHeatChamber chamber(int id, String wkt) throws Exception {
        return new ExistingHeatChamber(TypedId.of(new BigDecimal(id)), point(wkt));
    }

    private static LineString line(String wkt) throws Exception { return (LineString) projected(wkt); }
    private static Point point(String wkt) throws Exception { return (Point) projected(wkt); }
    private static Point geographicPoint(String wkt) throws Exception {
        return (Point) new WKTReader().read(wkt);
    }
    private static Geometry projected(String wkt) throws Exception {
        Geometry geometry = new WKTReader().read(wkt);
        geometry.setSRID(32637);
        return geometry;
    }
    private static void assertPoint(double x, double y, Point actual) {
        assertEquals(x, actual.getX(), 1e-9);
        assertEquals(y, actual.getY(), 1e-9);
    }
}
