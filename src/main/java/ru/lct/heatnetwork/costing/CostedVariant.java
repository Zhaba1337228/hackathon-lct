package ru.lct.heatnetwork.costing;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.validation.ValidationReport;

/** A validated variant with per-element costs and its summary. */
public final class CostedVariant {
    private final Variant variant;
    private final ValidationReport validation;
    private final VariantCostSummary summary;
    private final Map<String, BigDecimal> segmentCostById;
    private final Map<String, BigDecimal> chamberCostByNodeId;

    CostedVariant(Variant variant, ValidationReport validation, VariantCostSummary summary,
            Map<String, BigDecimal> segmentCostById, Map<String, BigDecimal> chamberCostByNodeId) {
        this.variant = variant;
        this.validation = validation;
        this.summary = summary;
        this.segmentCostById = Collections.unmodifiableMap(new LinkedHashMap<>(segmentCostById));
        this.chamberCostByNodeId = Collections.unmodifiableMap(new LinkedHashMap<>(chamberCostByNodeId));
    }

    public Variant getVariant() { return variant; }
    public ValidationReport getValidation() { return validation; }
    public VariantCostSummary getSummary() { return summary; }
    public Map<String, BigDecimal> getSegmentCostById() { return segmentCostById; }
    public Map<String, BigDecimal> getChamberCostByNodeId() { return chamberCostByNodeId; }
}
