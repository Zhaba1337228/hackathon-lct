package ru.lct.heatnetwork.network;

import org.locationtech.jts.geom.Point;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.projection.CrsTransform;

/** One existing projected heat chamber. */
public final class ExistingHeatChamber {
    private final TypedId id;
    private final Point location;

    public ExistingHeatChamber(TypedId id, Point location) {
        if (id == null || location == null || location.isEmpty()
                || location.getSRID() != CrsTransform.PROJECTED_SRID
                || !Double.isFinite(location.getX()) || !Double.isFinite(location.getY())) {
            throw new IllegalArgumentException("Existing heat chamber requires an ID and a projected point");
        }
        this.id = id;
        this.location = (Point) location.copy();
    }

    public TypedId getId() { return id; }
    public Point getLocation() { return (Point) location.copy(); }
}
