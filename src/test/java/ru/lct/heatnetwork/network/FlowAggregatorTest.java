package ru.lct.heatnetwork.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowAggregatorTest {
    private final FlowAggregator aggregator = new FlowAggregator();

    @Test
    void sumsTargetFlowsOnceAcrossSharedTrunk() {
        Map<String, BigDecimal> flows = aggregator.aggregate(Arrays.asList(
                edge("a", "target-a", "junction"), edge("b", "target-b", "junction"),
                edge("shared", "junction", "source")), Map.of(
                        "target-a", new BigDecimal("2.5"), "target-b", new BigDecimal("3.25")));
        assertEquals(new BigDecimal("2.5"), flows.get("a"));
        assertEquals(new BigDecimal("3.25"), flows.get("b"));
        assertEquals(new BigDecimal("5.75"), flows.get("shared"));
    }

    @Test
    void rejectsCyclesSplitsAndUnattachedDemand() {
        assertThrows(IllegalArgumentException.class, () -> aggregator.aggregate(Arrays.asList(
                edge("ab", "a", "b"), edge("ba", "b", "a")), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> aggregator.aggregate(Arrays.asList(
                edge("a", "x", "root-a"), edge("b", "x", "root-b")), Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> aggregator.aggregate(
                Collections.singletonList(edge("a", "x", "root")),
                Collections.singletonMap("missing", BigDecimal.ONE)));
    }

    private static NetworkEdge edge(String id, String from, String to) {
        return new NetworkEdge(id, from, to, 10, null);
    }
}
