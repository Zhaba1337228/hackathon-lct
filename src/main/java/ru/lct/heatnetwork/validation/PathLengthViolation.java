package ru.lct.heatnetwork.validation;

import java.math.BigDecimal;

/** A continuous same-diameter run on a target's path exceeds its published maximum. */
public final class PathLengthViolation {
    private final String targetNode;
    private final int diameterMm;
    private final BigDecimal actualLengthM;
    private final BigDecimal maximumLengthM;

    public PathLengthViolation(String targetNode, int diameterMm, BigDecimal actualLengthM,
            BigDecimal maximumLengthM) {
        this.targetNode = targetNode;
        this.diameterMm = diameterMm;
        this.actualLengthM = actualLengthM;
        this.maximumLengthM = maximumLengthM;
    }
    public String getTargetNode() { return targetNode; }
    public int getDiameterMm() { return diameterMm; }
    public BigDecimal getActualLengthM() { return actualLengthM; }
    public BigDecimal getMaximumLengthM() { return maximumLengthM; }
}
