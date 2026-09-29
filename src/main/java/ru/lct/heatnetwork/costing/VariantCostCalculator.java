package ru.lct.heatnetwork.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.rules.DiameterRule;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.validation.ValidationReport;

/** Calculates the construction price and score only after independent full validation. */
public final class VariantCostCalculator {
    private static final BigDecimal DISCONNECTED_FIXED_RUB = new BigDecimal("100000000");
    private static final BigDecimal DISCONNECTED_PER_TPH_RUB = new BigDecimal("500000");
    private static final BigDecimal SCORE_COST_DIVISOR = new BigDecimal("25000000");
    private static final BigDecimal SCORE_LENGTH_DIVISOR = new BigDecimal("100");
    private static final BigDecimal COST_WEIGHT = new BigDecimal("0.7");
    private static final BigDecimal LENGTH_WEIGHT = new BigDecimal("0.3");
    private static final int MONEY_SCALE = 2;

    public CostedVariant calculate(Variant variant, ValidationReport validation,
            List<FeatureIndex.IndexedFeature> inputs, Collection<TypedId> unconnectedTargetIds) {
        return calculate(variant, validation, inputs, unconnectedTargetIds, Collections.emptyList());
    }

    /** Search status is diagnostic; every target absent from the variant incurs the specified penalty. */
    public CostedVariant calculate(Variant variant, ValidationReport validation,
            List<FeatureIndex.IndexedFeature> inputs, Collection<TypedId> unconnectedTargetIds,
            Collection<TypedId> unsearchedTargetIds) {
        if (variant == null || validation == null || !validation.isAcceptable()
                || inputs == null || unconnectedTargetIds == null || unsearchedTargetIds == null) {
            throw new IllegalArgumentException("A validated variant, input features and target outcome IDs are required");
        }
        Map<TypedId, FeatureIndex.IndexedFeature> inputById = indexInputs(inputs);
        TargetPartition targets = targetPartition(variant, inputById, unconnectedTargetIds, unsearchedTargetIds);

        BigDecimal lineCost = BigDecimal.ZERO;
        BigDecimal totalLength = BigDecimal.ZERO;
        Map<String, BigDecimal> segmentCosts = new LinkedHashMap<>();
        Map<String, Variant.Node> nodes = new HashMap<>();
        for (Variant.Node node : variant.getNodes()) nodes.put(node.getId(), node);
        int tieInCount = 0;
        for (Variant.Segment segment : variant.getSegments()) {
            if (segment.getGeometry() == null || !Double.isFinite(segment.getGeometry().getLength())
                    || segment.getGeometry().getLength() <= 0) {
                throw new IllegalArgumentException("Every priced segment requires a positive projected geometry length");
            }
            DiameterRule diameter = RuleCatalog.diameter(segment.getDiameterMm())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown pipe diameter: " + segment.getDiameterMm()));
            BigDecimal multiplier = segmentMultiplier(segment);
            BigDecimal length = BigDecimal.valueOf(segment.getGeometry().getLength());
            BigDecimal cost = money(length.multiply(diameter.getPricePerMeter()).multiply(multiplier));
            segmentCosts.put(segment.getId(), cost);
            lineCost = lineCost.add(cost);
            totalLength = totalLength.add(length);
            if (isExistingChamber(nodes.get(segment.getStartNodeId()), inputById)) tieInCount++;
            if (isExistingChamber(nodes.get(segment.getEndNodeId()), inputById)) tieInCount++;
        }

        Map<String, BigDecimal> chamberCosts = new LinkedHashMap<>();
        for (Variant.Node node : variant.getNodes()) {
            if (!"heat_chamber".equals(node.getType()) || isExistingChamber(node, inputById)) continue;
            int maximumDiameter = maximumIncidentDiameter(node, variant);
            FeatureIndex.IndexedFeature source = node.getInputFeatureId() == null ? null : inputById.get(node.getInputFeatureId());
            if (source != null && "heat_network".equals(source.getFeature().getObjectType())) {
                maximumDiameter = Math.max(maximumDiameter, integerProperty(source.getFeature(), "diameter"));
            }
            if (maximumDiameter <= 0) throw new IllegalArgumentException("New chamber has no adjacent pipe diameter");
            chamberCosts.put(node.getId(), RuleCatalog.chamberCost(maximumDiameter));
        }
        BigDecimal chamberCost = sum(chamberCosts.values());
        BigDecimal tieInCost = money(RuleCatalog.existingChamberTieInCost().multiply(BigDecimal.valueOf(tieInCount)));
        BigDecimal constructionCost = money(lineCost.add(chamberCost).add(tieInCost));
        BigDecimal penalty = unconnectedPenalty(targets.unconnected, inputById);
        BigDecimal calculatedCost = money(constructionCost.add(penalty));
        BigDecimal score = calculatedCost.divide(SCORE_COST_DIVISOR, 12, RoundingMode.HALF_UP)
                .multiply(COST_WEIGHT)
                .add(totalLength.divide(SCORE_LENGTH_DIVISOR, 12, RoundingMode.HALF_UP).multiply(LENGTH_WEIGHT))
                .setScale(7, RoundingMode.HALF_UP);

        VariantCostSummary summary = new VariantCostSummary(constructionCost, chamberCost, tieInCount,
                tieInCost, penalty, calculatedCost, totalLength, score, targets.unconnected,
                targets.unsearched);
        return new CostedVariant(variant, validation, summary, segmentCosts, chamberCosts);
    }

    private static Map<TypedId, FeatureIndex.IndexedFeature> indexInputs(List<FeatureIndex.IndexedFeature> inputs) {
        Map<TypedId, FeatureIndex.IndexedFeature> result = new LinkedHashMap<>();
        for (FeatureIndex.IndexedFeature indexed : inputs) {
            if (indexed == null || indexed.getFeature() == null || indexed.getFeature().getId() == null) {
                throw new IllegalArgumentException("Costing input contains an object without a typed ID");
            }
            if (result.put(indexed.getFeature().getId(), indexed) != null) {
                throw new IllegalArgumentException("Costing input contains duplicate typed IDs");
            }
        }
        return result;
    }

    private static TargetPartition targetPartition(Variant variant,
            Map<TypedId, FeatureIndex.IndexedFeature> inputs, Collection<TypedId> unconnectedIds,
            Collection<TypedId> unsearchedIds) {
        Set<TypedId> allTargets = new LinkedHashSet<>();
        for (FeatureIndex.IndexedFeature indexed : inputs.values()) {
            if ("oks_connection_point".equals(indexed.getFeature().getObjectType())) allTargets.add(indexed.getFeature().getId());
        }
        Set<TypedId> connected = new LinkedHashSet<>();
        for (Variant.Node node : variant.getNodes()) {
            if (!"oks_connection_point".equals(node.getType())) continue;
            if (node.getInputFeatureId() == null || !connected.add(node.getInputFeatureId())) {
                throw new IllegalArgumentException("Variant target links must be unique typed IDs");
            }
        }
        Set<TypedId> unconnected = new LinkedHashSet<>();
        for (TypedId id : unconnectedIds) {
            if (id == null || !unconnected.add(id) || !allTargets.contains(id)) {
                throw new IllegalArgumentException("Unconnected target IDs must be unique IDs from the input");
            }
        }
        Set<TypedId> unsearched = new LinkedHashSet<>();
        for (TypedId id : unsearchedIds) {
            if (id == null || !unsearched.add(id) || !allTargets.contains(id)) {
                throw new IllegalArgumentException("Unsearched target IDs must be unique IDs from the input");
            }
        }
        Set<TypedId> union = new LinkedHashSet<>(connected);
        for (TypedId id : unconnected) {
            if (!union.add(id)) throw new IllegalArgumentException("A target cannot be both connected and unconnected");
        }
        for (TypedId id : unsearched) {
            if (!union.add(id)) throw new IllegalArgumentException("A target cannot have multiple outcome states");
        }
        if (!union.equals(allTargets)) throw new IllegalArgumentException("Every input target must be connected or explicitly unconnected");
        for (TypedId id : connected) {
            FeatureIndex.IndexedFeature input = inputs.get(id);
            if (input == null || !"oks_connection_point".equals(input.getFeature().getObjectType())) {
                throw new IllegalArgumentException("Variant references a missing or mistyped target input");
            }
        }
        Set<TypedId> missing = new LinkedHashSet<>(allTargets);
        missing.removeAll(connected);
        return new TargetPartition(new ArrayList<>(missing), new ArrayList<>(unsearched));
    }

    private static BigDecimal unconnectedPenalty(List<TypedId> ids,
            Map<TypedId, FeatureIndex.IndexedFeature> inputs) {
        BigDecimal result = BigDecimal.ZERO;
        for (TypedId id : ids) {
            JsonNode flow = inputs.get(id).getFeature().getProperties().getExtra().get("flow_tph");
            if (flow == null || !flow.isNumber() || flow.decimalValue().signum() <= 0) {
                throw new IllegalArgumentException("Unconnected target requires a positive flow_tph");
            }
            result = result.add(DISCONNECTED_FIXED_RUB.add(DISCONNECTED_PER_TPH_RUB.multiply(flow.decimalValue())));
        }
        return money(result);
    }

    private static boolean isExistingChamber(Variant.Node node,
            Map<TypedId, FeatureIndex.IndexedFeature> inputs) {
        if (node == null || node.getInputFeatureId() == null) return false;
        FeatureIndex.IndexedFeature input = inputs.get(node.getInputFeatureId());
        return input != null && "heat_chamber".equals(input.getFeature().getObjectType());
    }

    private static int maximumIncidentDiameter(Variant.Node node, Variant variant) {
        int maximum = 0;
        for (Variant.Segment segment : variant.getSegments()) {
            if (segment.getStartNodeId().equals(node.getId()) || segment.getEndNodeId().equals(node.getId())) {
                if (segment.getDiameterMm() == null) throw new IllegalArgumentException("Chamber-adjacent segment has no diameter");
                maximum = Math.max(maximum, segment.getDiameterMm());
            }
        }
        return maximum;
    }

    private static int integerProperty(InputFeature feature, String name) {
        JsonNode value = feature.getProperties().getExtra().get(name);
        if (value == null || !value.canConvertToInt()) throw new IllegalArgumentException("Existing network requires integer " + name);
        return value.intValue();
    }

    private static BigDecimal segmentMultiplier(Variant.Segment segment) {
        if (!"base".equals(segment.getLayingMethod()) && !"special".equals(segment.getLayingMethod())) {
            throw new IllegalArgumentException("Unknown segment laying method: " + segment.getLayingMethod());
        }
        BigDecimal multiplier = segment.getMultiplier();
        if (multiplier == null) {
            if ("special".equals(segment.getLayingMethod())) throw new IllegalArgumentException("Special segment requires a multiplier");
            multiplier = RuleCatalog.baseMultiplier();
        }
        if (multiplier.signum() <= 0) throw new IllegalArgumentException("Segment multiplier must be positive");
        return multiplier;
    }

    private static BigDecimal sum(Collection<BigDecimal> values) {
        BigDecimal result = BigDecimal.ZERO;
        for (BigDecimal value : values) result = result.add(value);
        return result;
    }

    private static BigDecimal money(BigDecimal amount) { return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP); }

    private static final class TargetPartition {
        private final List<TypedId> unconnected;
        private final List<TypedId> unsearched;
        private TargetPartition(List<TypedId> unconnected, List<TypedId> unsearched) {
            this.unconnected = Collections.unmodifiableList(unconnected);
            this.unsearched = Collections.unmodifiableList(unsearched);
        }
    }
}
