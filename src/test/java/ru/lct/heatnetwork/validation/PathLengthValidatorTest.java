package ru.lct.heatnetwork.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.lct.heatnetwork.network.NetworkEdge;

class PathLengthValidatorTest {
    private final PathLengthValidator validator = new PathLengthValidator();

    @Test
    void countsSameDiameterAcrossChambersAndTechnicalNodesAndChecksEachBranch() {
        List<NetworkEdge> edges = Arrays.asList(
                edge("a1", "target-a", "chamber", 100, 50), edge("a2", "chamber", "junction", 90, 50),
                edge("b", "target-b", "junction", 170, 50), edge("shared", "junction", "source", 20, 50));
        List<PathLengthViolation> violations = validator.validate(edges, Arrays.asList("target-a", "target-b"));
        assertEquals(2, violations.size());
        assertEquals("target-a", violations.get(0).getTargetNode());
        assertEquals(210.0, violations.get(0).getActualLengthM().doubleValue(), 1e-9);
        assertEquals("target-b", violations.get(1).getTargetNode());
        assertEquals(190.0, violations.get(1).getActualLengthM().doubleValue(), 1e-9);
    }

    @Test
    void diameterChangeResetsRunButDiameterMayNotDecreaseTowardSource() {
        List<NetworkEdge> valid = Arrays.asList(edge("a", "target", "technical", 100, 65),
                edge("b", "technical", "source", 245, 80));
        assertTrue(validator.validate(valid, Collections.singletonList("target")).isEmpty());
        List<NetworkEdge> decreasing = Arrays.asList(edge("a", "target", "technical", 100, 80),
                edge("b", "technical", "source", 100, 65));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(decreasing, Collections.singletonList("target")));
    }

    @Test
    void rejectsUnlistedDiameterAndMissingTargetPath() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                Collections.singletonList(edge("a", "target", "source", 10, 60)),
                Collections.singletonList("target")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Collections.emptyList(), Collections.singletonList("target")));
    }

    private static NetworkEdge edge(String id, String from, String to, double length, int diameter) {
        return new NetworkEdge(id, from, to, length, diameter);
    }
}
