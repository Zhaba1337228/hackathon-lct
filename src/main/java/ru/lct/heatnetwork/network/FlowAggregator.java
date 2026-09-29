package ru.lct.heatnetwork.network;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates consumer demand toward source-side roots on a directed forest. */
public final class FlowAggregator {
    public Map<String, BigDecimal> aggregate(Collection<NetworkEdge> edges,
            Map<String, BigDecimal> demandByNodeTph) {
        if (edges == null || demandByNodeTph == null) throw new IllegalArgumentException("Edges and demands are required");
        Map<String, NetworkEdge> parentEdge = new LinkedHashMap<>();
        Map<String, List<NetworkEdge>> children = new HashMap<>();
        Set<String> nodes = new HashSet<>();
        Set<String> edgeIds = new HashSet<>();
        for (NetworkEdge edge : edges) {
            if (edge == null || !edgeIds.add(edge.getId())) throw new IllegalArgumentException("Duplicate or null edge");
            if (parentEdge.put(edge.getFromNode(), edge) != null) {
                throw new IllegalArgumentException("A node cannot split flow into multiple parent edges: " + edge.getFromNode());
            }
            nodes.add(edge.getFromNode());
            nodes.add(edge.getToNode());
            children.computeIfAbsent(edge.getToNode(), ignored -> new ArrayList<>()).add(edge);
        }
        for (Map.Entry<String, BigDecimal> demand : demandByNodeTph.entrySet()) {
            if (demand.getKey() == null || demand.getValue() == null || demand.getValue().signum() <= 0
                    || !nodes.contains(demand.getKey())) {
                throw new IllegalArgumentException("Demand must be positive and belong to a network node");
            }
        }

        Map<String, Integer> remainingChildren = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        for (String node : nodes) {
            int count = children.getOrDefault(node, Collections.emptyList()).size();
            remainingChildren.put(node, count);
            if (count == 0) ready.add(node);
        }
        Map<String, BigDecimal> nodeFlow = new HashMap<>();
        for (String node : nodes) nodeFlow.put(node, demandByNodeTph.getOrDefault(node, BigDecimal.ZERO));
        Map<String, BigDecimal> edgeFlow = new LinkedHashMap<>();
        int processed = 0;
        while (!ready.isEmpty()) {
            String node = ready.removeFirst();
            processed++;
            NetworkEdge parent = parentEdge.get(node);
            if (parent == null) continue;
            BigDecimal flow = nodeFlow.get(node);
            edgeFlow.put(parent.getId(), flow);
            String parentNode = parent.getToNode();
            nodeFlow.put(parentNode, nodeFlow.get(parentNode).add(flow));
            int count = remainingChildren.compute(parentNode, (ignored, value) -> value - 1);
            if (count == 0) ready.addLast(parentNode);
        }
        if (processed != nodes.size()) throw new IllegalArgumentException("Network contains a directed cycle");
        return Collections.unmodifiableMap(edgeFlow);
    }
}
