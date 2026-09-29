package ru.lct.heatnetwork.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VariantTest {
    @Test
    void keepsStableGeneratedIdsAndReferences() {
        Variant variant = new Variant("variant-1",
                Arrays.asList(new Variant.Node("node-1", 0, 0, "technical_node"),
                        new Variant.Node("node-2", 1, 1, "oks_connection_point")),
                Arrays.asList(new Variant.Segment("segment-1", "node-1", "node-2")));

        assertEquals("node-1", variant.getSegments().get(0).getStartNodeId());
        assertEquals("node-2", variant.getSegments().get(0).getEndNodeId());
        assertThrows(IllegalArgumentException.class, () -> new Variant("broken",
                Arrays.asList(new Variant.Node("node-1", 0, 0, "technical_node")),
                Arrays.asList(new Variant.Segment("segment-1", "node-1", "missing"))));
    }
}
