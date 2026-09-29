package ru.lct.heatnetwork.costing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.validation.ValidationContext;
import ru.lct.heatnetwork.validation.ValidationReport;
import ru.lct.heatnetwork.validation.VariantValidator;

class VariantSelectorTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void keepsTheCheapestValidatedGeometryAndDropsSmallShiftsOfTheSameRoute() {
        List<FeatureIndex.IndexedFeature> inputs = inputs();
        VariantCostCalculator calculator = new VariantCostCalculator();
        List<CostedVariant> candidates = Arrays.asList(
                costed("direct", coordinates(0), inputs, calculator),
                costed("small-shift", coordinates(0.25), inputs, calculator),
                costed("detour", coordinates(3.0), inputs, calculator));

        List<CostedVariant> selected = new VariantSelector().select(candidates, 3, 1.0);

        assertEquals(2, selected.size());
        assertEquals("direct", selected.get(0).getVariant().getId());
        assertEquals("detour", selected.get(1).getVariant().getId());
        assertTrue(selected.get(0).getSummary().getScore().compareTo(selected.get(1).getSummary().getScore()) < 0);
    }

    @Test
    void capsOutputAtThreeVariants() {
        List<CostedVariant> candidates = new ArrayList<>();
        List<FeatureIndex.IndexedFeature> inputs = inputs();
        VariantCostCalculator calculator = new VariantCostCalculator();
        for (int i = 0; i < 4; i++) candidates.add(costed("offset-" + i, coordinates(i * 3.0), inputs, calculator));

        assertEquals(3, new VariantSelector().select(candidates, 3, 0.5).size());
    }

    @Test
    void preservesInclusionMaximalTargetSetsBeforeRankingByScore() {
        CostedVariant cheaperSubset = selectionCandidate("only-a", 1, "A");
        CostedVariant completeSuperset = selectionCandidate("a-and-b", 10, "A", "B");
        CostedVariant independentSet = selectionCandidate("only-c", 5, "C");

        List<CostedVariant> selected = new VariantSelector().select(
                Arrays.asList(cheaperSubset, completeSuperset, independentSet), 3, 0.5);

        assertEquals(2, selected.size());
        assertEquals("only-c", selected.get(0).getVariant().getId());
        assertEquals("a-and-b", selected.get(1).getVariant().getId());
    }

    @Test
    void retainsTheLargestValidatedTargetSetWithinThreeScoreRankedOptions() {
        CostedVariant cheapA = selectionCandidate("only-a", 1, "A");
        CostedVariant cheapB = selectionCandidate("only-b", 2, "B");
        CostedVariant cheapC = selectionCandidate("only-c", 3, "C");
        CostedVariant largest = selectionCandidate("lower-district", 100, "D", "E", "F");
        List<CostedVariant> candidates = Arrays.asList(cheapA, cheapB, cheapC, largest);

        List<CostedVariant> selected = new VariantSelector().select(candidates, 3, 0.5);

        assertEquals(Arrays.asList("only-a", "only-b", "lower-district"), Arrays.asList(
                selected.get(0).getVariant().getId(), selected.get(1).getVariant().getId(),
                selected.get(2).getVariant().getId()));
        assertEquals("only-a", new VariantSelector().select(candidates, 1, 0.5)
                .get(0).getVariant().getId(), "single-result mode keeps the score winner");
    }

    private static CostedVariant selectionCandidate(String id, long score, String... targetIds) {
        List<Variant.Node> nodes = new ArrayList<>();
        for (String targetId : targetIds) {
            nodes.add(new Variant.Node(id + ':' + targetId, nodes.size(), 0,
                    "oks_connection_point", TypedId.of(targetId)));
        }
        Variant variant = new Variant(id, nodes, Collections.emptyList());
        BigDecimal value = BigDecimal.valueOf(score);
        VariantCostSummary summary = new VariantCostSummary(value, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, value, value, value, Collections.emptyList());
        return new CostedVariant(variant,
                new ValidationReport(Collections.emptyList(), Collections.emptyList()), summary,
                Collections.emptyMap(), Collections.emptyMap());
    }

    private static CostedVariant costed(String id, Coordinate[] coordinates,
            List<FeatureIndex.IndexedFeature> inputs, VariantCostCalculator calculator) {
        TypedId targetId = TypedId.of("target");
        TypedId chamberId = TypedId.of("chamber");
        String targetNode = id + ":target";
        String chamberNode = id + ":chamber";
        LineString geometry = FACTORY.createLineString(coordinates);
        Variant variant = new Variant(id, Arrays.asList(
                new Variant.Node(targetNode, 0, 0, "oks_connection_point", targetId),
                new Variant.Node(chamberNode, 100, 0, "heat_chamber", chamberId)),
                Collections.singletonList(new Variant.Segment(id + ":pipe", targetNode, chamberNode, geometry,
                        targetNode, chamberNode, new BigDecimal("20"), 100, "base", Collections.emptySet(), BigDecimal.ONE)));
        ValidationReport validation = new VariantValidator().validate(variant, new ValidationContext(inputs));
        assertTrue(validation.isAcceptable(), () -> validation.getIssues().stream().map(issue -> issue.getCode()).toString());
        return calculator.calculate(variant, validation, inputs, Collections.emptyList());
    }

    private static Coordinate[] coordinates(double offset) {
        return new Coordinate[] {new Coordinate(0, 0), new Coordinate(50, offset), new Coordinate(100, 0)};
    }

    private static List<FeatureIndex.IndexedFeature> inputs() {
        TypedId targetId = TypedId.of("target");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("chamber");
        FeatureProperties target = new FeatureProperties(targetId, "oks_connection_point");
        target.putExtra("flow_tph", MAPPER.valueToTree(20.0));
        FeatureProperties network = new FeatureProperties(networkId, "heat_network");
        network.putExtra("diameter", MAPPER.valueToTree(100));
        return Arrays.asList(
                indexed(target, FACTORY.createPoint(new Coordinate(0, 0))),
                indexed(network, FACTORY.createLineString(new Coordinate[] {new Coordinate(100, 0), new Coordinate(120, 0)})),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), FACTORY.createPoint(new Coordinate(100, 0))));
    }

    private static FeatureIndex.IndexedFeature indexed(FeatureProperties properties, Geometry geometry) {
        return new FeatureIndex.IndexedFeature(new InputFeature(properties, NullNode.getInstance()), geometry);
    }
}
