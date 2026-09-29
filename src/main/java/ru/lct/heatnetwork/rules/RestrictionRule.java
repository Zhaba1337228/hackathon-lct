package ru.lct.heatnetwork.rules;

import java.math.BigDecimal;

/** Immutable clearance and crossing policy for one restriction kind. */
public final class RestrictionRule {
    public enum Crossing { PROHIBITED, SPECIAL_PASSAGE, FINAL_APPROACH_ONLY }

    private final String type;
    private final Crossing crossing;
    private final BigDecimal minimumClearanceM;
    private final BigDecimal specialMultiplier;
    private final boolean pipeDiameterDependent;

    public RestrictionRule(String type, Crossing crossing, String minimumClearanceM,
            String specialMultiplier, boolean pipeDiameterDependent) {
        this.type = type;
        this.crossing = crossing;
        this.minimumClearanceM = new BigDecimal(minimumClearanceM);
        this.specialMultiplier = new BigDecimal(specialMultiplier);
        this.pipeDiameterDependent = pipeDiameterDependent;
    }

    public String getType() { return type; }
    public Crossing getCrossing() { return crossing; }
    public BigDecimal getSpecialMultiplier() { return specialMultiplier; }
    public boolean isPipeDiameterDependent() { return pipeDiameterDependent; }

    public BigDecimal minimumClearanceM(int diameterMm) {
        if (!pipeDiameterDependent) return minimumClearanceM;
        if (diameterMm < 500) return new BigDecimal("5.0");
        if (diameterMm <= 800) return new BigDecimal("7.0");
        return new BigDecimal("9.0");
    }
}
