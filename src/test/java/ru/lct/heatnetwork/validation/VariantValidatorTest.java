package ru.lct.heatnetwork.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.io.WKTReader;
import org.junit.jupiter.api.Test;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.spatial.FeatureIndex;

class VariantValidatorTest {
    private final VariantValidator validator = new VariantValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void structurallyValidTargetHasOneSourceButReportRemainsIncomplete() {
        Variant variant = new Variant("valid", Arrays.asList(
                node("attachment", 2, 0, "technical_node"), node("technical", 1, 0, "technical_node"),
                node("target", 0, 0, "oks_connection_point")), Arrays.asList(
                        edge("s-t", "attachment", "technical"), edge("t-o", "technical", "target")));

        ValidationReport report = validator.validate(variant);

        assertTrue(report.isValid());
        assertFalse(report.isComplete());
        assertFalse(report.isAcceptable());
        assertTrue(report.getIssues().isEmpty());
        assertTrue(report.getChecksNotRun().contains("input_feature_links"));
    }

    @Test
    void reportsCyclesBranchesAndTurnsWithElementIds() {
        Variant cyclic = new Variant("cycle", Arrays.asList(node("source", 0, 0, "technical_node"),
                node("target", 1, 0, "oks_connection_point"), node("camera", 0, 1, "heat_chamber")),
                Arrays.asList(edge("a", "source", "target"), edge("b", "target", "camera"),
                        edge("c", "camera", "source")));
        ValidationReport cycleReport = validator.validate(cyclic);
        assertTrue(cycleReport.getIssues().stream().anyMatch(i -> "NETWORK_CYCLE".equals(i.getCode())));

        Variant bent = new Variant("turn", Arrays.asList(node("source", -1, 0, "source"),
                node("bend", 0, 0, "technical_node"), node("target", -0.5, 0.866, "oks_connection_point")),
                Arrays.asList(edge("a", "source", "bend"), edge("b", "bend", "target")));
        assertTrue(validator.validate(bent).getIssues().stream()
                .anyMatch(i -> "TURN_ANGLE_OVER_90".equals(i.getCode()) && "bend".equals(i.getElementId())));

        Variant branch = new Variant("branch", Arrays.asList(node("source", 0, 0, "technical_node"),
                node("target", 1, 0, "oks_connection_point"), node("x", 0, 1, "technical_node"),
                node("y", 0, -1, "technical_node")), Arrays.asList(edge("a", "source", "target"),
                        edge("b", "source", "x"), edge("c", "source", "y")));
        assertTrue(validator.validate(branch).getIssues().stream()
                .anyMatch(i -> "BRANCH_WITHOUT_CHAMBER".equals(i.getCode())));
    }

    @Test
    void reportsMixedPointAndLineOverlapOutsideCommonNodeWithoutThrowing() throws Exception {
        Variant variant = new Variant("mixed-intersection", Arrays.asList(
                node("common", 2, 0, "heat_chamber"),
                node("first-end", 0, 4, "technical_node"),
                node("second-end", 2, 4, "technical_node")), Arrays.asList(
                segment("first", "common", "first-end",
                        "LINESTRING (2 0, 4 0, 4 4, 0 4)", "first-end", "common",
                        "base", Collections.emptySet(), BigDecimal.ONE),
                segment("second", "common", "second-end",
                        "LINESTRING (2 0, 6 0, 2 4)", "second-end", "common",
                        "base", Collections.emptySet(), BigDecimal.ONE)));

        ValidationReport report = validator.validate(variant, new ValidationContext(Collections.emptyList()));

        assertTrue(report.getIssues().stream().anyMatch(issue ->
                "NEW_SEGMENTS_INTERSECT".equals(issue.getCode())));
    }

    @Test
    void checksEachIncomingBranchTurnAtThreeWayChamber() throws Exception {
        Variant variant = new Variant("branch-turn", Arrays.asList(
                node("first", 0, 0, "oks_connection_point"),
                node("second", 7, 4, "oks_connection_point"),
                node("branch", 5, 0, "heat_chamber"),
                node("root", 10, 0, "heat_chamber")), Arrays.asList(
                segment("first-leg", "first", "branch", "LINESTRING (0 0, 5 0)",
                        "first", "branch", "base", Collections.emptySet(), BigDecimal.ONE),
                segment("second-leg", "second", "branch", "LINESTRING (7 4, 5 0)",
                        "second", "branch", "base", Collections.emptySet(), BigDecimal.ONE),
                segment("shared", "branch", "root", "LINESTRING (5 0, 10 0)",
                        "branch", "root", "base", Collections.emptySet(), BigDecimal.ONE)));

        ValidationReport report = validator.validate(variant);

        assertTrue(report.getIssues().stream().anyMatch(issue ->
                "TURN_ANGLE_OVER_90".equals(issue.getCode()) && "branch".equals(issue.getElementId())));
    }

    @Test
    void reportsUnconnectedTargetsAndTooManyChamberConnections() {
        Variant variant = new Variant("bad", Arrays.asList(node("source", 0, 0, "source"),
                node("target", 10, 0, "oks_connection_point"), node("chamber", 1, 1, "heat_chamber"),
                node("a", 1, 2, "technical_node"), node("b", 2, 1, "technical_node"),
                node("c", 1, 0, "technical_node"), node("d", 0, 1, "technical_node"),
                node("e", 2, 2, "technical_node")),
                Arrays.asList(edge("1", "chamber", "a"), edge("2", "chamber", "b"),
                        edge("3", "chamber", "c"), edge("4", "chamber", "d"), edge("5", "chamber", "e")));
        ValidationReport report = validator.validate(variant);
        assertTrue(report.getIssues().stream().anyMatch(i -> "TARGET_UNCONNECTED".equals(i.getCode())));
        assertTrue(report.getIssues().stream().anyMatch(i -> "CHAMBER_CONNECTION_LIMIT".equals(i.getCode())));
    }

    @Test
    void rejectsAnOverNinetyDegreeTurnInsideASingleLineString() throws Exception {
        Variant variant = new Variant("internal-turn", Arrays.asList(
                node("target", 0, 0, "oks_connection_point"), node("attachment", 9, -2, "technical_node")),
                Collections.singletonList(new Variant.Segment("bent-line", "target", "attachment",
                        line("LINESTRING (0 0, 10 0, 9 -2)"), "target", "attachment",
                        new BigDecimal("3.0"), 50, "base", Collections.emptySet(), BigDecimal.ONE)));

        assertTrue(validator.validate(variant).getIssues().stream()
                .anyMatch(i -> "TURN_ANGLE_OVER_90".equals(i.getCode()) && "bent-line".equals(i.getElementId())));
    }

    @Test
    void acceptsRoundTripNoiseAtRightAngleButStillRejectsARealAcuteTurn() throws Exception {
        Variant nearRight = new Variant("near-right", Arrays.asList(
                node("target", 0, 0, "oks_connection_point"),
                node("attachment", 9.99999999, 10, "technical_node")),
                Collections.singletonList(segment("near-right-line", "target", "attachment",
                        "LINESTRING (0 0, 10 0, 9.99999999 10)", "target", "attachment",
                        "base", Collections.emptySet(), BigDecimal.ONE)));
        Variant acute = new Variant("acute", Arrays.asList(
                node("target", 0, 0, "oks_connection_point"),
                node("attachment", 9.99, 10, "technical_node")),
                Collections.singletonList(segment("acute-line", "target", "attachment",
                        "LINESTRING (0 0, 10 0, 9.99 10)", "target", "attachment",
                        "base", Collections.emptySet(), BigDecimal.ONE)));

        assertFalse(validator.validate(nearRight).getIssues().stream().anyMatch(issue ->
                "TURN_ANGLE_OVER_90".equals(issue.getCode())));
        assertTrue(validator.validate(acute).getIssues().stream().anyMatch(issue ->
                "TURN_ANGLE_OVER_90".equals(issue.getCode())));
    }

    @Test
    void checksTurnAtCorrectEndOfQuarterMeterSegment() throws Exception {
        Variant variant = new Variant("short-leg-right-angle", Arrays.asList(
                node("target", 0, 0, "oks_connection_point"),
                node("bend", 0.25, 0, "heat_chamber"),
                node("root", 0.25, 1, "heat_chamber")), Arrays.asList(
                segment("short", "target", "bend", "LINESTRING (0 0, 0.25 0)",
                        "target", "bend", "base", Collections.emptySet(), BigDecimal.ONE),
                segment("sourceward", "bend", "root", "LINESTRING (0.25 0, 0.25 1)",
                        "bend", "root", "base", Collections.emptySet(), BigDecimal.ONE)));

        assertFalse(validator.validate(variant).getIssues().stream().anyMatch(issue ->
                "TURN_ANGLE_OVER_90".equals(issue.getCode()) && "bend".equals(issue.getElementId())));
    }

    @Test
    void rejectsASelfReturningSegmentEvenWhenEveryLocalTurnIsLegal() throws Exception {
        LineString loop = line("LINESTRING (-1 0, 0 0, 1 0, 1 1, 0 1, 0 0, -1 -1)");
        assertTrue(loop.isValid());
        assertFalse(loop.isSimple());
        Variant variant = new Variant("self-returning", Arrays.asList(
                node("target", -1, 0, "oks_connection_point"),
                node("attachment", -1, -1, "technical_node")),
                Collections.singletonList(new Variant.Segment("loop", "target", "attachment",
                        loop, "target", "attachment", new BigDecimal("3.0"), 50,
                        "base", Collections.emptySet(), BigDecimal.ONE)));

        assertTrue(validator.validate(variant).getIssues().stream().anyMatch(issue ->
                "SEGMENT_GEOMETRY_NOT_SIMPLE".equals(issue.getCode())
                        && "loop".equals(issue.getElementId())));
    }

    @Test
    void independentlyChecksSegmentGeometryInputLinksFlowAndMinimalDiameter() throws Exception {
        TypedId targetId = TypedId.of("target-id");
        TypedId networkId = TypedId.of(new BigDecimal("44"));
        Variant variant = new Variant("full", Arrays.asList(
                new Variant.Node("target-node", 0, 5, "oks_connection_point", targetId),
                new Variant.Node("attachment-node", 10, 0, "heat_chamber", networkId)),
                Collections.singletonList(new Variant.Segment("new-network", "target-node", "attachment-node",
                        line("LINESTRING (0 5, 10 0)"), "target-node", "attachment-node",
                        new BigDecimal("3.0"), 50, "base", Collections.emptySet(), BigDecimal.ONE)));
        ValidationContext context = new ValidationContext(Arrays.asList(
                indexed(targetId, "oks_connection_point", "POINT (0 5)", "flow_tph", new BigDecimal("3.0")),
                indexed(networkId, "heat_network", "LINESTRING (0 0, 10 0)", "diameter", 500)));

        ValidationReport report = validator.validate(variant, context);

        assertTrue(report.getIssues().isEmpty(), () -> report.getIssues().toString());
        assertTrue(report.isAcceptable());
        assertFalse(report.getChecksNotRun().contains("flow_diameter_and_path_length"));
        assertFalse(report.getChecksNotRun().contains("input_feature_links"));
    }

    @Test
    void catchesFlowMismatchAndGeometryEndpointDrift() throws Exception {
        TypedId targetId = TypedId.of("target-id");
        TypedId networkId = TypedId.of("network-id");
        Variant variant = new Variant("bad-full", Arrays.asList(
                new Variant.Node("target-node", 0, 5, "oks_connection_point", targetId),
                new Variant.Node("attachment-node", 10, 0, "heat_chamber", networkId)),
                Collections.singletonList(new Variant.Segment("new-network", "target-node", "attachment-node",
                        line("LINESTRING (0 5, 11 0)"), "target-node", "attachment-node",
                        new BigDecimal("2.0"), 50, "base", Collections.emptySet(), BigDecimal.ONE)));
        ValidationContext context = new ValidationContext(Arrays.asList(
                indexed(targetId, "oks_connection_point", "POINT (0 5)", "flow_tph", new BigDecimal("3.0")),
                indexed(networkId, "heat_network", "LINESTRING (0 0, 10 0)", "diameter", 500)));

        List<ValidationIssue> issues = validator.validate(variant, context).getIssues();

        assertTrue(issues.stream().anyMatch(i -> "SEGMENT_ENDPOINT_MISMATCH".equals(i.getCode())));
        assertTrue(issues.stream().anyMatch(i -> "SEGMENT_FLOW_MISMATCH".equals(i.getCode())));
    }

    @Test
    void rejectsProhibitedCrossingAndAcceptsAProperlySplitRoadPassage() throws Exception {
        TypedId targetId = TypedId.of("target-id");
        TypedId networkId = TypedId.of("network-id");
        TypedId roadId = TypedId.of("road-id");
        Variant crossingPark = new Variant("park-crossing", Arrays.asList(
                new Variant.Node("target", 0, 5, "oks_connection_point", targetId),
                new Variant.Node("attachment", 10, 0, "heat_chamber", networkId)),
                Collections.singletonList(new Variant.Segment("line", "target", "attachment",
                        line("LINESTRING (0 5, 10 0)"), "target", "attachment",
                        new BigDecimal("3.0"), 50, "base", Collections.emptySet(), BigDecimal.ONE)));
        ValidationContext parkContext = new ValidationContext(Arrays.asList(
                indexed(targetId, "oks_connection_point", "POINT (0 5)", "flow_tph", new BigDecimal("3.0")),
                indexed(networkId, "heat_network", "LINESTRING (10 -2, 10 2)", "diameter", 500),
                indexed(roadId, "restriction", "POLYGON ((4 2, 6 2, 6 4, 4 4, 4 2))",
                        "restriction_type", "park")));
        assertTrue(validator.validate(crossingPark, parkContext).getIssues().stream()
                .anyMatch(i -> "SPATIAL_CLEARANCE_VIOLATION".equals(i.getCode())));

        Variant roadPassage = new Variant("road-passage", Arrays.asList(
                new Variant.Node("target", 0, 0, "oks_connection_point", targetId),
                node("tech-a", 5, 0, "technical_node"), node("tech-b", 15, 0, "technical_node"),
                new Variant.Node("attachment", 20, 0, "heat_chamber", networkId)), Arrays.asList(
                segment("base-a", "target", "tech-a", "LINESTRING (0 0, 5 0)", "target", "tech-a",
                        "base", Collections.emptySet(), BigDecimal.ONE),
                segment("road", "tech-a", "tech-b", "LINESTRING (5 0, 15 0)", "tech-a", "tech-b",
                        "special", Collections.singleton(roadId), new BigDecimal("1.60")),
                segment("base-b", "tech-b", "attachment", "LINESTRING (15 0, 20 0)", "tech-b", "attachment",
                        "base", Collections.emptySet(), BigDecimal.ONE)));
        ValidationContext roadContext = new ValidationContext(Arrays.asList(
                indexed(targetId, "oks_connection_point", "POINT (0 0)", "flow_tph", new BigDecimal("3.0")),
                indexed(networkId, "heat_network", "LINESTRING (20 -2, 20 2)", "diameter", 500),
                indexed(roadId, "restriction", "POLYGON ((8 -5, 12 -5, 12 5, 8 5, 8 -5))",
                        "restriction_type", "road")));
        ValidationReport valid = validator.validate(roadPassage, roadContext);
        assertTrue(valid.getIssues().isEmpty(), () -> valid.getIssues().toString());
        assertTrue(valid.isAcceptable());
    }

    @Test
    void exemptsOnlyTheTargetBuildingsStraightFinalApproach() throws Exception {
        TypedId targetId = TypedId.of("target-id");
        TypedId networkId = TypedId.of("network-id");
        Variant variant = new Variant("own-building", Arrays.asList(
                new Variant.Node("target", 0, 0, "oks_connection_point", targetId),
                new Variant.Node("attachment", 10, 0, "heat_chamber", networkId)),
                Collections.singletonList(segment("line", "target", "attachment", "LINESTRING (0 0, 10 0)",
                        "target", "attachment", "base", Collections.emptySet(), BigDecimal.ONE)));
        ValidationContext ownOnly = new ValidationContext(Arrays.asList(
                indexed(targetId, "oks_connection_point", "POINT (0 0)", "flow_tph", new BigDecimal("3.0")),
                indexed(networkId, "heat_network", "LINESTRING (10 -2, 10 2)", "diameter", 500),
                indexed(TypedId.of("building"), "restriction", "POLYGON ((-10 -10, 4.5 -10, 4.5 10, -10 10, -10 -10))",
                        "restriction_type", "oks")));
        ValidationReport ownOnlyReport = validator.validate(variant, ownOnly);
        assertTrue(ownOnlyReport.isAcceptable(), () -> ownOnlyReport.getIssues().stream()
                .map(i -> i.getCode() + ":" + i.getMessage()).collect(java.util.stream.Collectors.toList()).toString()
                + " notRun=" + ownOnlyReport.getChecksNotRun());

        Variant bentBeforeClearance = new Variant("own-building-bent-before-clearance", Arrays.asList(
                new Variant.Node("target", 0, 0, "oks_connection_point", targetId),
                new Variant.Node("attachment", 10, 0, "heat_chamber", networkId)),
                Collections.singletonList(segment("line", "target", "attachment",
                        "LINESTRING (0 0, 4.5 0, 9.5 1, 10 0)", "target", "attachment",
                        "base", Collections.emptySet(), BigDecimal.ONE)));
        ValidationReport bentReport = validator.validate(bentBeforeClearance, ownOnly);
        assertTrue(bentReport.getIssues().stream().anyMatch(issue -> "OWN_OKS_APPROACH_INVALID".equals(issue.getCode())),
                () -> bentReport.getIssues().stream().map(issue -> issue.getCode() + ":" + issue.getMessage())
                        .collect(java.util.stream.Collectors.toList()).toString());

        ValidationContext withPark = new ValidationContext(Arrays.asList(
                indexed(targetId, "oks_connection_point", "POINT (0 0)", "flow_tph", new BigDecimal("3.0")),
                indexed(networkId, "heat_network", "LINESTRING (10 -2, 10 2)", "diameter", 500),
                indexed(TypedId.of("building"), "restriction", "POLYGON ((-10 -10, 4.5 -10, 4.5 10, -10 10, -10 -10))",
                        "restriction_type", "oks"),
                indexed(TypedId.of("park"), "restriction", "POLYGON ((7 -1, 8 -1, 8 1, 7 1, 7 -1))",
                        "restriction_type", "park")));
        assertTrue(validator.validate(variant, withPark).getIssues().stream()
                .anyMatch(i -> "SPATIAL_CLEARANCE_VIOLATION".equals(i.getCode())));
    }

    @Test
    void rejectsTransitThroughAnotherWingOfTheTargetBuilding() throws Exception {
        TypedId targetId = TypedId.of("target-id"), networkId = TypedId.of("network-id");
        Variant variant = new Variant("reentry", Arrays.asList(
                new Variant.Node("target", 2, 6, "oks_connection_point", targetId),
                new Variant.Node("attachment", 20, 6, "heat_chamber", networkId)),
                Collections.singletonList(segment("line", "target", "attachment", "LINESTRING (2 6, 20 6)",
                        "target", "attachment", "base", Collections.emptySet(), BigDecimal.ONE)));
        ValidationContext context = new ValidationContext(Arrays.asList(
                indexed(targetId, "oks_connection_point", "POINT (2 6)", "flow_tph", new BigDecimal("3.0")),
                indexed(networkId, "heat_network", "LINESTRING (20 4, 20 8)", "diameter", 500),
                indexed(TypedId.of("building"), "restriction",
                        "POLYGON ((0 0, 10 0, 10 10, 7 10, 7 3, 3 3, 3 10, 0 10, 0 0))",
                        "restriction_type", "oks")));
        ValidationReport report = validator.validate(variant, context);
        assertFalse(report.isAcceptable());
        assertTrue(report.getIssues().stream().anyMatch(issue -> "OWN_OKS_APPROACH_INVALID".equals(issue.getCode())));
    }

    private static Variant.Node node(String id, double x, double y, String type) {
        return new Variant.Node(id, x, y, type);
    }
    private static Variant.Segment edge(String id, String from, String to) {
        return new Variant.Segment(id, from, to);
    }

    private Variant.Segment segment(String id, String start, String end, String wkt,
            String flowFrom, String flowTo, String method, java.util.Set<TypedId> restrictions,
            BigDecimal multiplier) throws Exception {
        return new Variant.Segment(id, start, end, line(wkt), flowFrom, flowTo,
                new BigDecimal("3.0"), 50, method, restrictions, multiplier);
    }

    private FeatureIndex.IndexedFeature indexed(TypedId id, String type, String wkt,
            String property, Object value) throws Exception {
        FeatureProperties properties = new FeatureProperties(id, type);
        if (value instanceof BigDecimal) properties.putExtra(property, mapper.valueToTree(value));
        else properties.putExtra(property, mapper.valueToTree(value));
        InputFeature feature = new InputFeature(properties, mapper.readTree("{\"type\":\"Point\",\"coordinates\":[0,0]}"));
        return new FeatureIndex.IndexedFeature(feature, projected(wkt));
    }

    private static LineString line(String wkt) throws Exception { return (LineString) projected(wkt); }
    private static Geometry projected(String wkt) throws Exception {
        Geometry geometry = new WKTReader().read(wkt);
        geometry.setSRID(32637);
        return geometry;
    }
}
