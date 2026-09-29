package ru.lct.heatnetwork.spatial;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.locationtech.jts.geom.LineString;
import ru.lct.heatnetwork.domain.TypedId;

/** One route fragment over which the active restriction set and construction method stay constant. */
public final class IntersectionSegment implements ClearanceRange {
    public enum Method { BASE, SPECIAL }

    private final double startMeasureM;
    private final double endMeasureM;
    private final LineString geometry;
    private final Set<TypedId> restrictionIds;
    private final BigDecimal multiplier;
    private final Method method;
    private final boolean startTechnicalNode;
    private final boolean endTechnicalNode;

    public IntersectionSegment(double startMeasureM, double endMeasureM, LineString geometry,
            Set<TypedId> restrictionIds, BigDecimal multiplier, boolean startTechnicalNode,
            boolean endTechnicalNode) {
        if (startMeasureM < 0 || endMeasureM <= startMeasureM) {
            throw new IllegalArgumentException("Segment measures must be increasing and non-negative");
        }
        this.startMeasureM = startMeasureM;
        this.endMeasureM = endMeasureM;
        this.geometry = geometry;
        this.restrictionIds = Collections.unmodifiableSet(new LinkedHashSet<>(restrictionIds));
        this.multiplier = multiplier;
        this.method = restrictionIds.isEmpty() ? Method.BASE : Method.SPECIAL;
        this.startTechnicalNode = startTechnicalNode;
        this.endTechnicalNode = endTechnicalNode;
    }

    @Override public double getStartMeasureM() { return startMeasureM; }
    @Override public double getEndMeasureM() { return endMeasureM; }
    public LineString getGeometry() { return geometry; }
    public Set<TypedId> getRestrictionIds() { return restrictionIds; }
    @Override public TypedId getRestrictionId() {
        if (restrictionIds.size() != 1) return null;
        return restrictionIds.iterator().next();
    }
    public BigDecimal getMultiplier() { return multiplier; }
    public Method getMethod() { return method; }
    public boolean isStartTechnicalNode() { return startTechnicalNode; }
    public boolean isEndTechnicalNode() { return endTechnicalNode; }
}
