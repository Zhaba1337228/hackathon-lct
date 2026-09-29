package ru.lct.heatnetwork.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.lct.heatnetwork.network.NetworkEdge;

/** Selects the smallest tabulated diameter satisfying both flow and continuous-path length. */
public final class DiameterSelector {
    public DiameterRule select(BigDecimal flowTph, BigDecimal continuousPathLengthM) {
        return RuleCatalog.smallestDiameter(flowTph, continuousPathLengthM)
                .orElseThrow(() -> new NoFeasibleDiameterException(flowTph, continuousPathLengthM));
    }

    /** Assigns diameters to a directed consumer-to-source forest, including shared-path length constraints. */
    public Map<String, DiameterRule> selectForTree(Collection<NetworkEdge> edges,
            Map<String, BigDecimal> flowByEdgeTph, Collection<String> targetNodes) {
        if (edges == null || flowByEdgeTph == null || targetNodes == null) {
            throw new IllegalArgumentException("Edges, flows, and target nodes are required");
        }
        Map<String, NetworkEdge> edgeById = new LinkedHashMap<>();
        Map<String, NetworkEdge> parentByNode = new HashMap<>();
        for (NetworkEdge edge : edges) {
            if (edge == null || edgeById.put(edge.getId(), edge) != null
                    || parentByNode.put(edge.getFromNode(), edge) != null) {
                throw new IllegalArgumentException("Network edges and parent paths must be unique");
            }
            BigDecimal flow = flowByEdgeTph.get(edge.getId());
            if (flow == null || flow.signum() <= 0) throw new IllegalArgumentException("Every edge needs positive flow");
        }
        if (flowByEdgeTph.size() != edgeById.size() || !edgeById.keySet().containsAll(flowByEdgeTph.keySet())) {
            throw new IllegalArgumentException("Flow map must match network edges exactly");
        }
        Map<String, DiameterRule> selected = new LinkedHashMap<>();
        for (NetworkEdge edge : edges) selected.put(edge.getId(), select(flowByEdgeTph.get(edge.getId()), BigDecimal.ZERO));
        List<String> orderedTargets = new ArrayList<>(targetNodes);
        if (orderedTargets.contains(null)) throw new IllegalArgumentException("Target nodes must not contain null");
        Collections.sort(orderedTargets);
        int maximumPasses = RuleCatalog.diameters().size() * Math.max(1, edges.size()) + 1;
        for (int pass = 0; pass < maximumPasses; pass++) {
            boolean changed = enforceMonotonicity(parentByNode, selected, flowByEdgeTph);
            for (String target : orderedTargets) {
                List<NetworkEdge> path = pathFromTarget(target, parentByNode);
                int first = 0;
                while (first < path.size()) {
                    int last = first + 1;
                    int diameter = selected.get(path.get(first).getId()).getDiameterMm();
                    double length = path.get(first).getLengthM();
                    BigDecimal maxFlow = flowByEdgeTph.get(path.get(first).getId());
                    while (last < path.size() && selected.get(path.get(last).getId()).getDiameterMm() == diameter) {
                        length += path.get(last).getLengthM();
                        maxFlow = maxFlow.max(flowByEdgeTph.get(path.get(last).getId()));
                        last++;
                    }
                    DiameterRule current = RuleCatalog.diameter(diameter).get();
                    if (BigDecimal.valueOf(length).compareTo(current.getMaxLengthM()) > 0) {
                        DiameterRule larger = smallestWithLimits(maxFlow, BigDecimal.valueOf(length), diameter);
                        for (int i = first; i < last; i++) {
                            String edgeId = path.get(i).getId();
                            if (selected.get(edgeId).getDiameterMm() != larger.getDiameterMm()) {
                                selected.put(edgeId, larger);
                                changed = true;
                            }
                        }
                    }
                    first = last;
                }
            }
            if (!changed) return Collections.unmodifiableMap(new LinkedHashMap<>(selected));
        }
        throw new IllegalStateException("Diameter selection did not converge");
    }

    private static boolean enforceMonotonicity(Map<String, NetworkEdge> parentByNode,
            Map<String, DiameterRule> selected, Map<String, BigDecimal> flowByEdgeTph) {
        boolean changed = false;
        for (NetworkEdge edge : parentByNode.values()) {
            NetworkEdge sourceward = parentByNode.get(edge.getToNode());
            if (sourceward == null) continue;
            int downstreamDiameter = selected.get(edge.getId()).getDiameterMm();
            if (selected.get(sourceward.getId()).getDiameterMm() < downstreamDiameter) {
                DiameterRule minimum = smallestWithLimits(flowByEdgeTph.get(sourceward.getId()), BigDecimal.ZERO,
                        downstreamDiameter);
                selected.put(sourceward.getId(), minimum);
                changed = true;
            }
        }
        return changed;
    }

    private static DiameterRule smallestWithLimits(BigDecimal flow, BigDecimal length, int minimumDiameter) {
        return RuleCatalog.diameters().stream().filter(rule -> rule.getDiameterMm() >= minimumDiameter
                && flow.compareTo(rule.getCapacityTph()) <= 0 && length.compareTo(rule.getMaxLengthM()) <= 0)
                .findFirst().orElseThrow(() -> new NoFeasibleDiameterException(flow, length));
    }

    private static List<NetworkEdge> pathFromTarget(String target, Map<String, NetworkEdge> parentByNode) {
        if (target == null || !parentByNode.containsKey(target)) {
            throw new IllegalArgumentException("Target node has no path toward a source: " + target);
        }
        List<NetworkEdge> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String node = target;
        while (parentByNode.containsKey(node)) {
            if (!visited.add(node)) throw new IllegalArgumentException("Network contains a directed cycle on target path " + target);
            NetworkEdge edge = parentByNode.get(node);
            path.add(edge);
            node = edge.getToNode();
        }
        return path;
    }
}
