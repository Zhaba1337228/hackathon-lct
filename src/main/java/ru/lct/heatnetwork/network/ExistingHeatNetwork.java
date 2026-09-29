package ru.lct.heatnetwork.network;

import org.locationtech.jts.geom.LineString;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.projection.CrsTransform;

/** One projected existing heat-network feature and its tabular pipe diameter. */
public final class ExistingHeatNetwork {
    private final TypedId id;
    private final LineString geometry;
    private final int diameterMm;

    public ExistingHeatNetwork(TypedId id, LineString geometry, int diameterMm) {
        if (id == null || geometry == null || geometry.isEmpty()
                || geometry.getSRID() != CrsTransform.PROJECTED_SRID
                || !Double.isFinite(geometry.getLength()) || geometry.getLength() <= 0 || diameterMm <= 0) {
            throw new IllegalArgumentException("Existing heat network requires an ID, projected line and positive diameter");
        }
        this.id = id;
        this.geometry = (LineString) geometry.copy();
        this.diameterMm = diameterMm;
    }

    public TypedId getId() { return id; }
    public LineString getGeometry() { return (LineString) geometry.copy(); }
    public int getDiameterMm() { return diameterMm; }
}
