package ru.lct.heatnetwork.costing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;

/** Selects up to three validated, meaningfully distinct variants in score order. */
public final class VariantSelector {
    public List<CostedVariant> select(List<CostedVariant> candidates, int maximumResults,
            double duplicateHausdorffToleranceM) {
        if (candidates == null || maximumResults < 1 || maximumResults > 3
                || !Double.isFinite(duplicateHausdorffToleranceM) || duplicateHausdorffToleranceM <= 0) {
            throw new IllegalArgumentException("Selection requires candidates, a limit from one to three and a positive geometry tolerance");
        }
        List<CostedVariant> ordered = new ArrayList<>();
        for (CostedVariant candidate : candidates) {
            if (candidate != null && candidate.getValidation() != null && candidate.getValidation().isAcceptable()) {
                ordered.add(candidate);
            }
        }
        Map<CostedVariant, Set<TypedId>> connectedByVariant = new HashMap<>();
        for (CostedVariant candidate : ordered) {
            connectedByVariant.put(candidate, targetIds(candidate.getVariant()));
        }
        List<CostedVariant> maximalCoverage = new ArrayList<>();
        for (CostedVariant candidate : ordered) {
            Set<TypedId> connected = connectedByVariant.get(candidate);
            boolean dominated = false;
            for (CostedVariant other : ordered) {
                Set<TypedId> otherConnected = connectedByVariant.get(other);
                if (otherConnected.size() > connected.size() && otherConnected.containsAll(connected)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) maximalCoverage.add(candidate);
        }
        Comparator<CostedVariant> scoreOrder = Comparator.comparing(
                (CostedVariant value) -> value.getSummary().getScore())
                .thenComparing(value -> value.getSummary().getCalculatedCost())
                .thenComparing(value -> value.getSummary().getNewNetworkLengthM())
                .thenComparing(value -> value.getVariant().getId());
        maximalCoverage.sort(scoreOrder);

        List<CostedVariant> selected = new ArrayList<>();
        for (CostedVariant candidate : maximalCoverage) {
            boolean duplicate = false;
            for (CostedVariant prior : selected) {
                if (sameTargetAndAttachmentSet(prior, candidate)
                        && hausdorffDistance(prior.getVariant(), candidate.getVariant()) <= duplicateHausdorffToleranceM) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) selected.add(candidate);
            if (selected.size() == maximumResults) break;
        }
        if (maximumResults > 1 && !maximalCoverage.isEmpty()) {
            int maximumConnected = maximalCoverage.stream()
                    .mapToInt(candidate -> connectedByVariant.get(candidate).size()).max().orElse(0);
            boolean coverageRetained = selected.stream()
                    .anyMatch(candidate -> connectedByVariant.get(candidate).size() == maximumConnected);
            if (!coverageRetained) {
                CostedVariant bestCoverage = maximalCoverage.stream()
                        .filter(candidate -> connectedByVariant.get(candidate).size() == maximumConnected)
                        .findFirst().orElseThrow(IllegalStateException::new);
                if (selected.size() == maximumResults) selected.remove(selected.size() - 1);
                selected.add(bestCoverage);
                selected.sort(scoreOrder);
            }
        }
        return java.util.Collections.unmodifiableList(selected);
    }

    private static boolean sameTargetAndAttachmentSet(CostedVariant first, CostedVariant second) {
        return targetIds(first.getVariant()).equals(targetIds(second.getVariant()))
                && first.getSummary().getUnconnectedTargetIds().equals(second.getSummary().getUnconnectedTargetIds())
                && attachmentIds(first.getVariant()).equals(attachmentIds(second.getVariant()));
    }

    private static Set<TypedId> targetIds(Variant variant) {
        Set<TypedId> result = new HashSet<>();
        for (Variant.Node node : variant.getNodes()) {
            if ("oks_connection_point".equals(node.getType())) result.add(node.getInputFeatureId());
        }
        return result;
    }

    private static List<String> attachmentIds(Variant variant) {
        java.util.Map<String, Integer> degreeByNode = new java.util.HashMap<>();
        for (Variant.Segment segment : variant.getSegments()) {
            degreeByNode.merge(segment.getStartNodeId(), 1, Integer::sum);
            degreeByNode.merge(segment.getEndNodeId(), 1, Integer::sum);
        }
        List<String> result = new ArrayList<>();
        for (Variant.Node node : variant.getNodes()) {
            if (degreeByNode.getOrDefault(node.getId(), 0) == 1 && !"oks_connection_point".equals(node.getType())) {
                String id = node.getInputFeatureId() == null ? "new" : typedId(node.getInputFeatureId());
                result.add(node.getType() + ":" + id);
            }
        }
        java.util.Collections.sort(result);
        return result;
    }

    private static String typedId(TypedId id) {
        return id.isNumeric() ? "number:" + id.asNumber().stripTrailingZeros().toPlainString()
                : "string:" + id.asString();
    }

    private static double hausdorffDistance(Variant first, Variant second) {
        Geometry firstGeometry = linework(first);
        Geometry secondGeometry = linework(second);
        DiscreteHausdorffDistance distance = new DiscreteHausdorffDistance(firstGeometry, secondGeometry);
        distance.setDensifyFraction(0.25);
        return distance.distance();
    }

    private static Geometry linework(Variant variant) {
        List<LineString> lines = new ArrayList<>();
        for (Variant.Segment segment : variant.getSegments()) lines.add(segment.getGeometry());
        if (lines.isEmpty()) throw new IllegalArgumentException("Variant has no network geometry");
        return lines.get(0).getFactory().createMultiLineString(lines.toArray(new LineString[0]));
    }
}
