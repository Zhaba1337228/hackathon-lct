package ru.lct.heatnetwork.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.projection.CrsTransform;

/** A projected location on existing network plus the chamber where a new route must terminate. */
public final class AttachmentCandidate {
    public enum EndpointKind { EXISTING_CHAMBER, NEW_CHAMBER }

    private final TypedId networkId;
    private final double measureM;
    private final Point insertionPoint;
    private final Point endpoint;
    private final EndpointKind endpointKind;
    private final TypedId existingChamberId;
    private final List<TypedId> connectedNetworkIds;
    private final int existingConnectionCount;
    private final int maximumExistingDiameterMm;

    public AttachmentCandidate(TypedId networkId, double measureM, Point insertionPoint, Point endpoint,
            EndpointKind endpointKind, TypedId existingChamberId, List<TypedId> connectedNetworkIds,
            int existingConnectionCount, int maximumExistingDiameterMm) {
        if (networkId == null || !Double.isFinite(measureM) || measureM < 0 || insertionPoint == null || endpoint == null
                || endpointKind == null || connectedNetworkIds == null || connectedNetworkIds.isEmpty()
                || existingConnectionCount < 1 || existingConnectionCount > 4 || maximumExistingDiameterMm <= 0
                || insertionPoint.isEmpty() || endpoint.isEmpty()
                || insertionPoint.getSRID() != CrsTransform.PROJECTED_SRID
                || endpoint.getSRID() != CrsTransform.PROJECTED_SRID
                || !Double.isFinite(insertionPoint.getX()) || !Double.isFinite(insertionPoint.getY())
                || !Double.isFinite(endpoint.getX()) || !Double.isFinite(endpoint.getY())
                || !connectedNetworkIds.contains(networkId)
                || new HashSet<>(connectedNetworkIds).size() != connectedNetworkIds.size()
                || (endpointKind == EndpointKind.EXISTING_CHAMBER
                    && (existingChamberId == null || existingConnectionCount >= 4))
                || (endpointKind == EndpointKind.NEW_CHAMBER
                    && (existingChamberId != null || existingConnectionCount > 2
                        || insertionPoint.distance(endpoint) != 0))) {
            throw new IllegalArgumentException("Invalid attachment candidate");
        }
        this.networkId = networkId;
        this.measureM = measureM;
        this.insertionPoint = (Point) insertionPoint.copy();
        this.endpoint = (Point) endpoint.copy();
        this.endpointKind = endpointKind;
        this.existingChamberId = existingChamberId;
        this.connectedNetworkIds = Collections.unmodifiableList(new ArrayList<>(connectedNetworkIds));
        this.existingConnectionCount = existingConnectionCount;
        this.maximumExistingDiameterMm = maximumExistingDiameterMm;
    }

    public TypedId getNetworkId() { return networkId; }
    /** Chainage on the selected existing LineString in metres. */
    public double getMeasureM() { return measureM; }
    /** Exact closest point on the existing network where a new chamber can be inserted. */
    public Point getInsertionPoint() { return (Point) insertionPoint.copy(); }
    /** Endpoint to which a proposed new route must connect. */
    public Point getEndpoint() { return (Point) endpoint.copy(); }
    public EndpointKind getEndpointKind() { return endpointKind; }
    public TypedId getExistingChamberId() { return existingChamberId; }
    public List<TypedId> getConnectedNetworkIds() { return connectedNetworkIds; }
    /** Existing pipe legs at this location; the proposed new route consumes one more chamber connection. */
    public int getExistingConnectionCount() { return existingConnectionCount; }
    /** Largest existing diameter connected at this location; combine with the proposed route diameter for chamber sizing. */
    public int getMaximumExistingDiameterMm() { return maximumExistingDiameterMm; }
}
