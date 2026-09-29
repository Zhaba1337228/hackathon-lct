package ru.lct.heatnetwork.rules;

import java.math.BigDecimal;

/** Immutable tabulated pipe diameter and its hydraulic, geometric and cost limits. */
public final class DiameterRule {
    private final int diameterMm;
    private final BigDecimal capacityTph;
    private final BigDecimal maxLengthM;
    private final BigDecimal pricePerMeter;
    private final BigDecimal pairWidthM;
    private final BigDecimal heightM;

    public DiameterRule(int diameterMm, String capacityTph, String maxLengthM, String pricePerMeter,
            String pairWidthM, String heightM) {
        this.diameterMm = diameterMm;
        this.capacityTph = new BigDecimal(capacityTph);
        this.maxLengthM = new BigDecimal(maxLengthM);
        this.pricePerMeter = new BigDecimal(pricePerMeter);
        this.pairWidthM = new BigDecimal(pairWidthM);
        this.heightM = new BigDecimal(heightM);
    }

    public int getDiameterMm() { return diameterMm; }
    public BigDecimal getCapacityTph() { return capacityTph; }
    public BigDecimal getMaxLengthM() { return maxLengthM; }
    public BigDecimal getPricePerMeter() { return pricePerMeter; }
    public BigDecimal getPairWidthM() { return pairWidthM; }
    public BigDecimal getHeightM() { return heightM; }
}
