package ru.lct.heatnetwork.costing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.validation.ValidationContext;
import ru.lct.heatnetwork.validation.ValidationReport;
import ru.lct.heatnetwork.validation.VariantValidator;

class VariantCostCalculatorTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final VariantCostCalculator calculator = new VariantCostCalculator();

    @Test
    void matchesIndependentHundredMeterDn100AndOneTieInReference() throws Exception {
        Fixture fixture = fixture(false);
        Variant variant = route("reference", fixture.targetId, fixture.chamberId, fixture.networkId,
                point(100, 0), line(point(0, 0), point(100, 0)), 100, 20.0, "base", BigDecimal.ONE);
        ValidationReport validation = validate(variant, fixture.inputs);

        CostedVariant result = calculator.calculate(variant, validation, fixture.inputs, Collections.emptyList());

        assertEquals(0, new BigDecimal("13974800.00").compareTo(result.getSummary().getConstructionCost()));
        assertEquals(0, new BigDecimal("13974800.00").compareTo(result.getSummary().getCalculatedCost()));
        assertEquals(1, result.getSummary().getExistingChamberTieInCount());
        assertEquals(0, new BigDecimal("8974800.00").compareTo(result.getSegmentCostById().get("reference:pipe")));
        assertEquals(0, new BigDecimal("0.6912944").compareTo(result.getSummary().getScore()));
    }

    @Test
    void chargesNewChamberOnceAndUsesTheLargestAdjacentPipeDiameter() throws Exception {
        Fixture fixture = fixture(true);
        Variant variant = route("new-chamber", fixture.targetId, null, fixture.networkId,
                point(100, 0), line(point(0, 0), point(100, 0)), 100, 20.0, "base", BigDecimal.ONE);
        ValidationReport validation = validate(variant, fixture.inputs);

        CostedVariant result = calculator.calculate(variant, validation, fixture.inputs, Collections.emptyList());

        assertEquals(0, new BigDecimal("5000000.00").compareTo(result.getSummary().getChamberConstructionCost()));
        assertEquals(0, new BigDecimal("0.00").compareTo(result.getSummary().getExistingChamberTieInCost()));
        assertEquals(0, new BigDecimal("13974800.00").compareTo(result.getSummary().getConstructionCost()));
        assertEquals(1, result.getChamberCostByNodeId().size());
    }

    @Test
    void chargesFixedAndFlowBasedPenaltyForEachExplicitlyUnconnectedTarget() throws Exception {
        Fixture fixture = fixture(false);
        TypedId unconnectedId = TypedId.of("unconnected");
        FeatureIndex.IndexedFeature unconnected = target(unconnectedId, 10.0, point(0, 5));
        java.util.List<FeatureIndex.IndexedFeature> fullInputs = Arrays.asList(
                fixture.inputs.get(0), fixture.inputs.get(1), fixture.inputs.get(2), unconnected);
        Variant variant = route("partial", fixture.targetId, fixture.chamberId, fixture.networkId,
                point(100, 0), line(point(0, 0), point(100, 0)), 100, 20.0, "base", BigDecimal.ONE);
        ValidationReport partialValidation = validate(variant, fixture.inputs);

        CostedVariant result = calculator.calculate(variant, partialValidation, fullInputs,
                Collections.singletonList(unconnectedId));

        assertEquals(0, new BigDecimal("105000000.00").compareTo(result.getSummary().getUnconnectedPenalty()));
        assertEquals(0, new BigDecimal("118974800.00").compareTo(result.getSummary().getCalculatedCost()));
        assertEquals(Collections.singletonList(unconnectedId), result.getSummary().getUnconnectedTargetIds());
    }

    @Test
    void chargesUnsearchedTargetBecauseItIsAbsentFromTheVariant() throws Exception {
        Fixture fixture = fixture(false);
        TypedId unsearchedId = TypedId.of("unsearched");
        FeatureIndex.IndexedFeature extra = target(unsearchedId, 10.0, point(0, 5));
        java.util.List<FeatureIndex.IndexedFeature> fullInputs = Arrays.asList(
                fixture.inputs.get(0), fixture.inputs.get(1), fixture.inputs.get(2), extra);
        Variant variant = route("bounded", fixture.targetId, fixture.chamberId, fixture.networkId,
                point(100, 0), line(point(0, 0), point(100, 0)), 100, 20.0, "base", BigDecimal.ONE);

        CostedVariant result = calculator.calculate(variant, validate(variant, fixture.inputs), fullInputs,
                Collections.emptyList(), Collections.singletonList(unsearchedId));

        assertEquals(Collections.singletonList(unsearchedId), result.getSummary().getUnsearchedTargetIds());
        assertEquals(new BigDecimal("105000000.00"), result.getSummary().getUnconnectedPenalty());
        assertEquals(Collections.singletonList(unsearchedId), result.getSummary().getUnconnectedTargetIds());
    }

    @Test
    void multipliesSpecialSegmentCostByItsRecordedCrossingMultiplier() throws Exception {
        Fixture fixture = fixture(false);
        Variant variant = route("special", fixture.targetId, fixture.chamberId, fixture.networkId,
                point(100, 0), line(point(0, 0), point(100, 0)), 100, 20.0, "special",
                new BigDecimal("1.60"));
        ValidationReport engineeringAccepted = new ValidationReport(Collections.emptyList(), Collections.emptyList());

        CostedVariant result = calculator.calculate(variant, engineeringAccepted, fixture.inputs, Collections.emptyList());

        assertEquals(0, new BigDecimal("19359680.00").compareTo(result.getSummary().getConstructionCost()));
    }

    private static ValidationReport validate(Variant variant, java.util.List<FeatureIndex.IndexedFeature> inputs) {
        ValidationReport result = new VariantValidator().validate(variant, new ValidationContext(inputs));
        assertTrue(result.isAcceptable(), () -> result.getIssues().stream()
                .map(issue -> issue.getCode()).reduce((a, b) -> a + "," + b).orElse("")
                + " checksNotRun=" + result.getChecksNotRun());
        return result;
    }

    private static Fixture fixture(boolean newChamber) throws Exception {
        TypedId targetId = TypedId.of("target");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = newChamber ? null : TypedId.of("chamber");
        FeatureIndex.IndexedFeature target = target(targetId, 20.0, point(0, 0));
        FeatureIndex.IndexedFeature network = indexed(properties(networkId, "heat_network", "diameter", newChamber ? 250 : 100),
                geometry("LINESTRING (100 0, 120 0)"));
        java.util.List<FeatureIndex.IndexedFeature> inputs = new java.util.ArrayList<>(Arrays.asList(target, network));
        if (chamberId != null) inputs.add(indexed(new FeatureProperties(chamberId, "heat_chamber"), point(100, 0)));
        return new Fixture(targetId, networkId, chamberId, inputs);
    }

    private static Variant route(String id, TypedId targetId, TypedId rootInputId, TypedId networkId,
            Point root, LineString geometry, double length, double flow, String layingMethod, BigDecimal multiplier) {
        String targetNode = id + ":target";
        String rootNode = id + ":root";
        String rootType = "heat_chamber";
        Variant.Node target = new Variant.Node(targetNode, geometry.getStartPoint().getX(),
                geometry.getStartPoint().getY(), "oks_connection_point", targetId);
        Variant.Node attachment = new Variant.Node(rootNode, root.getX(), root.getY(), rootType,
                rootInputId == null ? networkId : rootInputId);
        Variant.Segment segment = new Variant.Segment(id + ":pipe", targetNode, rootNode, geometry,
                targetNode, rootNode, BigDecimal.valueOf(flow), 100, layingMethod,
                Collections.emptySet(), multiplier);
        return new Variant(id, Arrays.asList(target, attachment), Collections.singletonList(segment));
    }

    private static FeatureIndex.IndexedFeature target(TypedId id, double flow, Point point) {
        return indexed(properties(id, "oks_connection_point", "flow_tph", flow), point);
    }

    private static FeatureProperties properties(TypedId id, String type, String name, Object value) {
        FeatureProperties properties = new FeatureProperties(id, type);
        properties.putExtra(name, MAPPER.valueToTree(value));
        return properties;
    }

    private static FeatureIndex.IndexedFeature indexed(FeatureProperties properties, Geometry geometry) {
        return new FeatureIndex.IndexedFeature(new InputFeature(properties, NullNode.getInstance()), geometry);
    }

    private static Point point(double x, double y) { return FACTORY.createPoint(new Coordinate(x, y)); }
    private static LineString line(Point start, Point end) {
        return FACTORY.createLineString(new Coordinate[] {start.getCoordinate(), end.getCoordinate()});
    }
    private static Geometry geometry(String wkt) throws Exception { return new WKTReader(FACTORY).read(wkt); }

    private static final class Fixture {
        private final TypedId targetId;
        private final TypedId networkId;
        private final TypedId chamberId;
        private final java.util.List<FeatureIndex.IndexedFeature> inputs;
        private Fixture(TypedId targetId, TypedId networkId, TypedId chamberId,
                java.util.List<FeatureIndex.IndexedFeature> inputs) {
            this.targetId = targetId;
            this.networkId = networkId;
            this.chamberId = chamberId;
            this.inputs = inputs;
        }
    }
}
