package ru.lct.heatnetwork.rules;

import java.math.BigDecimal;

/** Inclusive maximum-DN interval and construction cost for a new chamber. */
public final class ChamberCostRule {
    private final int minDiameterMm;
    private final int maxDiameterMm;
    private final BigDecimal costRub;

    public ChamberCostRule(int minDiameterMm, int maxDiameterMm, String costRub) {
        this.minDiameterMm = minDiameterMm;
        this.maxDiameterMm = maxDiameterMm;
        this.costRub = new BigDecimal(costRub);
        if (minDiameterMm > maxDiameterMm || this.costRub.signum() < 0) {
            throw new IllegalArgumentException("Invalid chamber cost interval");
        }
    }

    public int getMinDiameterMm() { return minDiameterMm; }
    public int getMaxDiameterMm() { return maxDiameterMm; }
    public BigDecimal getCostRub() { return costRub; }
    public boolean contains(int diameterMm) {
        return diameterMm >= minDiameterMm && diameterMm <= maxDiameterMm;
    }
}
