package ru.lct.heatnetwork.validation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.lct.heatnetwork.network.NetworkEdge;
import ru.lct.heatnetwork.rules.DiameterRule;
import ru.lct.heatnetwork.rules.RuleCatalog;

/** Checks each target-to-source path independently; shared edges are counted once per path. */
public final class PathLengthValidator {
    public List<PathLengthViolation> validate(Collection<NetworkEdge> edges, Collection<String> targetNodes) {
        if (edges == null || targetNodes == null) throw new IllegalArgumentException("Edges and target nodes are required");
        Map<String, NetworkEdge> parentEdge = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (NetworkEdge edge : edges) {
            if (edge == null || !edgeIds.add(edge.getId()) || parentEdge.put(edge.getFromNode(), edge) != null) {
                throw new IllegalArgumentException("Network must have unique edges and at most one parent per node");
            }
            if (edge.getDiameterMm() == null || !RuleCatalog.diameter(edge.getDiameterMm()).isPresent()) {
                throw new IllegalArgumentException("Every checked edge must have a tabulated diameter");
            }
        }
        List<PathLengthViolation> violations = new ArrayList<>();
        for (String target : targetNodes) {
            if (target == null || !parentEdge.containsKey(target)) {
                throw new IllegalArgumentException("Target node has no path toward a source: " + target);
            }
            validatePath(target, parentEdge, violations);
        }
        return Collections.unmodifiableList(violations);
    }

    private static void validatePath(String target, Map<String, NetworkEdge> parentEdge,
            List<PathLengthViolation> violations) {
        Set<String> visited = new HashSet<>();
        String node = target;
        Integer previousDiameter = null;
        BigDecimal runLength = BigDecimal.ZERO;
        while (parentEdge.containsKey(node)) {
            if (!visited.add(node)) throw new IllegalArgumentException("Network contains a directed cycle on target path " + target);
            NetworkEdge edge = parentEdge.get(node);
            int diameter = edge.getDiameterMm();
            if (previousDiameter != null && diameter < previousDiameter) {
                throw new IllegalArgumentException("Diameter decreases toward the source on target path " + target);
            }
            if (previousDiameter != null && diameter != previousDiameter) {
                addViolation(target, previousDiameter, runLength, violations);
                runLength = BigDecimal.ZERO;
            }
            runLength = runLength.add(BigDecimal.valueOf(edge.getLengthM()));
            previousDiameter = diameter;
            node = edge.getToNode();
        }
        if (previousDiameter != null) addViolation(target, previousDiameter, runLength, violations);
    }

    private static void addViolation(String target, int diameter, BigDecimal length,
            List<PathLengthViolation> violations) {
        DiameterRule rule = RuleCatalog.diameter(diameter).get();
        if (length.compareTo(rule.getMaxLengthM()) > 0) {
            violations.add(new PathLengthViolation(target, diameter, length, rule.getMaxLengthM()));
        }
    }
}
