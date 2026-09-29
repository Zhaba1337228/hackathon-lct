package ru.lct.heatnetwork.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.lct.heatnetwork.network.FlowAggregator;
import ru.lct.heatnetwork.network.NetworkEdge;

class DiameterSelectorTest {
    private final DiameterSelector selector = new DiameterSelector();

    @Test
    void selectsSmallestTabulatedDiameterFittingFlowAndLength() {
        assertEquals(50, selector.select(new BigDecimal("3.5"), new BigDecimal("181")).getDiameterMm());
        assertEquals(65, selector.select(new BigDecimal("3.5"), new BigDecimal("181.01")).getDiameterMm());
        assertEquals(65, selector.select(new BigDecimal("3.51"), new BigDecimal("100")).getDiameterMm());
    }

    @Test
    void rejectsFlowOrLengthBeyondAllTabulatedRules() {
        assertThrows(NoFeasibleDiameterException.class,
                () -> selector.select(new BigDecimal("22501.91"), BigDecimal.ONE));
        assertThrows(NoFeasibleDiameterException.class,
                () -> selector.select(BigDecimal.ONE, new BigDecimal("11276.01")));
    }

    @Test
    void selectsSharedTrunkDiameterWithoutSummingParallelBranchLengths() {
        java.util.List<NetworkEdge> edges = Arrays.asList(
                new NetworkEdge("a", "target-a", "junction", 120, null),
                new NetworkEdge("b", "target-b", "junction", 120, null),
                new NetworkEdge("shared", "junction", "source", 70, null));
        Map<String, BigDecimal> flows = new FlowAggregator().aggregate(edges, Map.of(
                "target-a", new BigDecimal("2.0"), "target-b", new BigDecimal("2.0")));
        Map<String, DiameterRule> selected = selector.selectForTree(edges, flows, Arrays.asList("target-a", "target-b"));
        assertEquals(50, selected.get("a").getDiameterMm());
        assertEquals(50, selected.get("b").getDiameterMm());
        assertEquals(65, selected.get("shared").getDiameterMm());
    }

    @Test
    void growsToTheSmallestDiameterThatSupportsTheFullContinuousPath() {
        java.util.List<NetworkEdge> edges = Arrays.asList(
                new NetworkEdge("first", "target", "technical", 100, null),
                new NetworkEdge("second", "technical", "source", 100, null));
        Map<String, BigDecimal> flows = new FlowAggregator().aggregate(edges,
                Map.of("target", new BigDecimal("2.0")));
        Map<String, DiameterRule> selected = selector.selectForTree(edges, flows,
                java.util.Collections.singletonList("target"));
        assertEquals(65, selected.get("first").getDiameterMm());
        assertEquals(65, selected.get("second").getDiameterMm());
    }

    @Test
    void sharedTreeDiameterDoesNotDependOnTargetIterationOrder() {
        java.util.List<NetworkEdge> edges = Arrays.asList(
                new NetworkEdge("first", "target:string:first", "junction", 150, null),
                new NetworkEdge("second", "target:string:second", "junction", 150, null),
                new NetworkEdge("shared", "junction", "source", 70, null));
        Map<String, BigDecimal> flows = new FlowAggregator().aggregate(edges, Map.of(
                "target:string:first", BigDecimal.ONE, "target:string:second", BigDecimal.ONE));

        Map<String, DiameterRule> firstThenSecond = selector.selectForTree(edges, flows,
                Arrays.asList("target:string:first", "target:string:second"));
        Map<String, DiameterRule> secondThenFirst = selector.selectForTree(edges, flows,
                Arrays.asList("target:string:second", "target:string:first"));

        assertEquals(firstThenSecond, secondThenFirst);
    }
}
