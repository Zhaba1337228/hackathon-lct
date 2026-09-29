package ru.lct.heatnetwork.spatial;

import ru.lct.heatnetwork.domain.TypedId;

/** A route interval exempt from clearance to exactly one named obstacle. */
public final class ClearanceExemption implements ClearanceRange {
    private final TypedId restrictionId;
    private final double startMeasureM;
    private final double endMeasureM;

    public ClearanceExemption(TypedId restrictionId, double startMeasureM, double endMeasureM) {
        if (restrictionId == null || !Double.isFinite(startMeasureM) || !Double.isFinite(endMeasureM)
                || startMeasureM < 0 || endMeasureM <= startMeasureM) {
            throw new IllegalArgumentException("Invalid clearance exemption");
        }
        this.restrictionId = restrictionId;
        this.startMeasureM = startMeasureM;
        this.endMeasureM = endMeasureM;
    }

    @Override public TypedId getRestrictionId() { return restrictionId; }
    @Override public double getStartMeasureM() { return startMeasureM; }
    @Override public double getEndMeasureM() { return endMeasureM; }
}
