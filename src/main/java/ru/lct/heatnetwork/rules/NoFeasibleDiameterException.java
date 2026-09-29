package ru.lct.heatnetwork.rules;

import java.math.BigDecimal;

/** Indicates that no published diameter supports both the requested flow and path length. */
public final class NoFeasibleDiameterException extends IllegalArgumentException {
    public NoFeasibleDiameterException(BigDecimal flowTph, BigDecimal lengthM) {
        super("No tabulated diameter supports flow " + flowTph + " tph over " + lengthM + " m");
    }
}
