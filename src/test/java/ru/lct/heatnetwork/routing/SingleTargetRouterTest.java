package ru.lct.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.network.ExistingHeatNetwork;
import ru.lct.heatnetwork.network.RouteTreeOptimizer;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.projection.Wgs84BoundingBox;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.validation.ValidationReport;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.spatial.ClearanceCheck;

class SingleTargetRouterTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SingleTargetRouter router = new SingleTargetRouter();

    @Test
    void treeGoalApproachUsesTheIndependentValidatorsAngularTolerance() {
        Point goal = point(0, 0);
        Coordinate sourceward = new Coordinate(1, 0);
        LineString justOverLimit = FACTORY.createLineString(new Coordinate[] {
                new Coordinate(5e-10, 1), new Coordinate(0, 0)});
        LineString perpendicular = FACTORY.createLineString(new Coordinate[] {
                new Coordinate(0, 1), new Coordinate(0, 0)});
        LineString allowedSide = FACTORY.createLineString(new Coordinate[] {
                new Coordinate(-5e-10, 1), new Coordinate(0, 0)});

        assertFalse(SingleTargetRouter.legalTreeGoalApproach(justOverLimit, goal, sourceward),
                "the old normalized -1e-9 allowance reports FOUND for a junction the validator rejects");
        assertTrue(SingleTargetRouter.legalTreeGoalApproach(perpendicular, goal, sourceward));
        assertTrue(SingleTargetRouter.legalTreeGoalApproach(allowedSide, goal, sourceward));
    }

    @Test
    void rejectsOwnBuildingReentryBeforeSpendingTheGraphBudget() throws Exception {
        Fixture fixture = fixture(Collections.singletonList(restriction("own", "oks",
                "POLYGON ((0 0, 10 0, 10 10, 7 10, 7 3, 3 3, 3 10, 0 10, 0 0))", null)),
                point(20, 6), point(2, 6));
        RouteCandidate result = new SingleTargetRouter(SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR, 1)
                .route(fixture.request(new Envelope(-10, 30, -10, 20), 2));
        assertFalse(result.isFound());
        assertEquals(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA, result.getStatus());
        assertTrue(result.getDetail().contains("crosses its own OKS again"), result.getDetail());
    }

    @Test
    void internalTurnsUseTheIndependentValidatorsAngularTolerance() {
        Coordinate previous = new Coordinate(0, 1);
        Coordinate current = new Coordinate(0, 0);

        assertFalse(SingleTargetRouter.legalTurn(previous, current, new Coordinate(1, 5e-10)),
                "an absolute dot tolerance would admit a turn the validator rejects");
        assertTrue(SingleTargetRouter.legalTurn(previous, current, new Coordinate(1, 0)),
                "an exact 90-degree turn is permitted");
        assertTrue(SingleTargetRouter.legalTurn(previous, current, new Coordinate(1, -5e-10)));
    }

    @Test
    void shortcutDoesNotMakeTheFollowingRightAngleIllegal() {
        Coordinate a = new Coordinate(0, 0);
        Coordinate b = new Coordinate(1, 0);
        Coordinate c = new Coordinate(1, 1);
        Coordinate d = new Coordinate(-1, 1);
        Coordinate fixedApproach = new Coordinate(0, -1);
        List<Coordinate> original = Arrays.asList(a, b, c, d);

        assertTrue(SingleTargetRouter.legalPolylineTurns(original, fixedApproach));
        assertFalse(SingleTargetRouter.legalPolylineTurns(Arrays.asList(a, c, d), fixedApproach),
                "a greedy A-C shortcut makes the following C-D turn 135 degrees");

        List<Coordinate> simplified = SingleTargetRouter.simplifyPath(original, fixedApproach,
                (from, to) -> !(from.equals2D(a) && to.equals2D(d)));

        assertEquals(original, simplified);
        assertTrue(SingleTargetRouter.legalPolylineTurns(simplified, fixedApproach));
    }

    @Test
    void connectsOneTargetToOneExistingNetworkCandidateAndPassesIndependentValidation() throws Exception {
        Fixture fixture = fixture(Collections.emptyList(), point(20, 0));
        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 25, -10, 10), 200));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertEquals(20.0, result.getGeometry().getLength(), 1e-9);
        assertEquals(50, result.getVariant().getSegments().get(0).getDiameterMm());
        assertTrue(result.getValidation().isAcceptable(), () -> validationDetails(result)
                + " notRun=" + result.getValidation().getChecksNotRun());
    }

    @Test
    void directClearRouteDoesNotConsumeVisibilityVertexBudget() throws Exception {
        Fixture fixture = fixture(Collections.emptyList(), point(20, 0));
        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 25, -10, 10), 2));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail());
        assertEquals(RouteCandidate.Status.FOUND, result.getStatus());
        assertEquals(20.0, result.getGeometry().getLength(), 1e-9);
    }

    @Test
    void localRightAngleBranchDoesNotNeedDistantObstacleVertices() throws Exception {
        verifyLocalRightAngleBranch(0.0);
        verifyLocalRightAngleBranch(0.37);
    }

    @Test
    void shortensADetouringPrefixWhileKeepingTheValidatedSouthernTail() throws Exception {
        LineString detour = line("LINESTRING (0 0, 10 0, 100 80, 120 60, 120 40, -20 -80, -30 -90, -60 -100)");
        Geometry protrusion = geometry("POLYGON ((9 -30, 11.5 -30, 11.5 -1, 9 -1, 9 -30))");
        Envelope area = new Envelope(-70, 130, -110, 90);
        LineString shortened = SingleTargetRouter.shortenLocalDetour(detour, point(10, 0), area,
                candidate -> candidate.distance(protrusion) >= 0.1);

        assertTrue(shortened.getLength() < 200 && shortened.getLength() < detour.getLength());
        assertTrue(shortened.isSimple());
        assertTrue(SingleTargetRouter.legalPolylineTurns(Arrays.asList(shortened.getCoordinates()), null));
        assertEquals(new Coordinate(0, 0), shortened.getCoordinateN(0));
        assertEquals(new Coordinate(10, 0), shortened.getCoordinateN(1), "keep the mandatory own-building exit");
        assertEquals(new Coordinate(12, 0), shortened.getCoordinateN(2),
                "a zero or one metre extension conflicts with the protruding obstacle");
        assertEquals(new Coordinate(-20, -80), shortened.getCoordinateN(shortened.getNumPoints() - 3));
        assertEquals(new Coordinate(-60, -100), shortened.getCoordinateN(shortened.getNumPoints() - 1));
        assertTrue(SingleTargetRouter.shortenLocalDetour(detour, point(10, 0), area,
                candidate -> false).equalsExact(detour), "rejected shortcuts must preserve the original candidate");
    }

    private void verifyLocalRightAngleBranch(double angle) throws Exception {
        AffineTransformation transform = AffineTransformation.rotationInstance(angle);
        transform.translate(414000, 6170000);
        Fixture seed = fixture(Collections.emptyList(), point(100, 0));
        List<FeatureIndex.IndexedFeature> rotatedInputs = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : seed.inputs) rotatedInputs.add(
                indexed(input.getFeature(), transform.transform(input.getGeometry())));
        Point root = (Point) transform.transform(point(100, 0));
        seed = new Fixture(rotatedInputs.get(0), seed.networkId, new AttachmentCandidate(seed.networkId,
                1.0, root, root, AttachmentCandidate.EndpointKind.NEW_CHAMBER, null,
                Collections.singletonList(seed.networkId), 2, 500), rotatedInputs);
        Envelope area = transform.transform(FACTORY.toGeometry(new Envelope(-10, 110, -15, 50)))
                .getEnvelopeInternal();
        RouteCandidate original = router.route(seed.request(area, 300));
        RouteTreeOptimizer optimizer = new RouteTreeOptimizer();
        RouteTreeOptimizer.TreeCandidate tree = optimizer.optimize("seed", Collections.singletonList(
                original.getVariant()), Collections.singletonList(seed.attachment), seed.inputs).getCandidate();
        FeatureIndex.IndexedFeature target = indexed(new InputFeature(properties(TypedId.of("turning-house"),
                "oks_connection_point", "flow_tph", 3.0), NullNode.getInstance()), transform.transform(point(50, 30)));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(seed.inputs);
        inputs.add(target);
        FeatureIndex.IndexedFeature own = restriction("own-house", "oks",
                "POLYGON ((40 10, 60 10, 60 31, 40 31, 40 10))", null);
        inputs.add(indexed(own.getFeature(), transform.transform(own.getGeometry())));
        String trunk = tree.getVariant().getSegments().get(0).getId();
        TreeRouteRequest request = new TreeRouteRequest("short-turn", target, tree, trunk,
                (Point) transform.transform(point(30, 0)), inputs, area, 2);
        SingleTargetRouter navigator = new SingleTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR, 1);

        SingleTargetRouter.BranchRoute result = navigator.routeToTree(request);

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail());
        assertTrue(result.getGeometry().isSimple());
        assertTrue(result.getGeometry().getLength() < 70.0,
                "the short north-west-south connector must replace a trip around distant obstacles");
        assertEquals(RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH, result.getStatus(),
                "a bounded local template is not a complete search");
        RouteTreeOptimizer.OptimizationResult attached = optimizer.attachRoutedBranch("accepted-short", tree,
                target, trunk, result.getGeometry(), result.getDiameterMm(), inputs);
        assertTrue(attached.isFound(), () -> attached.getDetail());
        assertTrue(attached.getValidation().isAcceptable());
        assertTrue(result.getGeometry().equalsExact(navigator.routeToTree(request).getGeometry()),
                "local template ordering must be deterministic");
        Point via = (Point) transform.transform(point(90, 40));
        SingleTargetRouter.BranchRoute viaResult = navigator.routeToTree(new TreeRouteRequest("via-turn",
                target, tree, trunk, request.getGoalPoint(), inputs, area, 2, via));
        assertTrue(!viaResult.isFound() || viaResult.getGeometry().distance(via) < 1e-6,
                "a short local connector must never bypass a mandatory corridor point");
        List<FeatureIndex.IndexedFeature> blockedInputs = new ArrayList<>(inputs);
        FeatureIndex.IndexedFeature wall = restriction("blocking-wall", "park",
                "POLYGON ((-200 5, 200 5, 200 8, -200 8, -200 5))", null);
        blockedInputs.add(indexed(wall.getFeature(), transform.transform(wall.getGeometry())));
        assertFalse(navigator.routeToTree(new TreeRouteRequest("blocked-turn", target, tree, trunk,
                request.getGoalPoint(), blockedInputs, area, 2)).isFound(),
                "a local shortcut must not cross a prohibited strip");
    }

    @Test
    void sectionSeedReservesClearanceWithoutInventingDemandOrOversizingActualPipe() throws Exception {
        FeatureIndex.IndexedFeature building = restriction("future-bottleneck", "oks",
                "POLYGON ((30 5.4, 70 5.4, 70 15, 30 15, 30 5.4))", null);
        Fixture fixture = fixture(Collections.singletonList(building), point(100, 0));
        Envelope area = new Envelope(-5, 105, -20, 25);
        RouteCandidate ordinary = router.route(fixture.request(area, 300));
        RouteRequest planned = new RouteRequest("reserved-section", fixture.target,
                fixture.attachment, fixture.inputs, area, 300, null, new BigDecimal("103"));
        RouteCandidate reserved = router.route(planned);
        assertTrue(ordinary.isFound(), () -> ordinary.getStatus() + ": " + ordinary.getDetail());
        assertTrue(reserved.isFound(), () -> reserved.getStatus() + ": " + reserved.getDetail()
                + validationDetails(reserved));
        double futureClearance = new ClearanceCheck().requiredCenterlineDistanceM("oks", 200,
                RuleCatalog.diameter(200).get().getPairWidthM(), null);
        assertTrue(ordinary.getGeometry().distance(building.getGeometry()) < futureClearance);
        assertTrue(reserved.getGeometry().distance(building.getGeometry()) >= futureClearance - 1e-8,
                "the shared corridor must leave space for the section diameter");
        assertTrue(reserved.getValidation().isAcceptable());
        assertTrue(reserved.getVariant().getSegments().stream().allMatch(segment ->
                segment.getFlowTph().compareTo(new BigDecimal("3")) == 0 && segment.getDiameterMm() == 50),
                "reserve changes geometry only, not actual flow or minimal seed diameter");

        FeatureIndex.IndexedFeature next = indexed(new InputFeature(properties(TypedId.of("next"),
                "oks_connection_point", "flow_tph", 100.0), NullNode.getInstance()), point(20, -10));
        List<FeatureIndex.IndexedFeature> fullInputs = new ArrayList<>(fixture.inputs);
        fullInputs.add(next);
        RouteTreeOptimizer optimizer = new RouteTreeOptimizer();
        RouteTreeOptimizer.TreeCandidate oldTree = optimizer.optimize("old", Collections.singletonList(
                ordinary.getVariant()), Collections.singletonList(fixture.attachment), fixture.inputs).getCandidate();
        RouteTreeOptimizer.TreeCandidate newTree = optimizer.optimize("new", Collections.singletonList(
                reserved.getVariant()), Collections.singletonList(fixture.attachment), fixture.inputs).getCandidate();
        assertFalse(oldTree.getVariant().getSegments().stream().anyMatch(segment -> optimizer.attachDirectBranch(
                "old-next", oldTree, next, segment.getId(), fullInputs).isFound()),
                "growing the old narrow corridor must reproduce the clearance rejection");
        assertTrue(newTree.getVariant().getSegments().stream().anyMatch(segment -> optimizer.attachDirectBranch(
                "new-next", newTree, next, segment.getId(), fullInputs).isFound()),
                "the reserved corridor must accept the short branch under full independent validation");
    }

    @Test
    void mandatoryCorridorPointSurvivesDirectShortcutAndSparseSearch() throws Exception {
        Fixture fixture = fixture(Collections.emptyList(), point(20, 0));
        Point via = point(10, 5);
        Envelope area = new Envelope(-5, 25, -10, 10);
        for (SingleTargetRouter.SearchMode mode : Arrays.asList(
                SingleTargetRouter.SearchMode.VISIBILITY,
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR)) {
            int vertices = mode == SingleTargetRouter.SearchMode.VISIBILITY ? 100 : 2;
            RouteRequest request = new RouteRequest("via-" + mode, fixture.target,
                    fixture.attachment, fixture.inputs, area, vertices, via, new BigDecimal("103"));
            RouteCandidate result = new SingleTargetRouter(mode).route(request);

            assertTrue(result.isFound(), () -> mode + ": " + result.getStatus() + ": "
                    + result.getDetail() + (result.getValidation() == null ? ""
                    : result.getValidation().getIssues().toString()));
            assertTrue(result.getGeometry().getLength() > 20.0);
            assertTrue(Arrays.stream(result.getGeometry().getCoordinates())
                    .anyMatch(vertex -> vertex.distance(via.getCoordinate()) < 1e-9),
                    "path simplification must not remove the mandatory point");
        }
        assertThrows(IllegalArgumentException.class, () -> new RouteRequest("outside-via",
                fixture.target, fixture.attachment, fixture.inputs, area, 16, point(30, 5)));
    }

    @Test
    void clearTwoLegCorridorDoesNotBuildAVisibilityGraph() throws Exception {
        Fixture fixture = fixture(Collections.emptyList(), point(20, 0));
        Point via = point(10, 5);
        RouteRequest request = new RouteRequest("direct-corridor", fixture.target,
                fixture.attachment, fixture.inputs, new Envelope(-5, 25, -10, 10), 2, via);

        RouteCandidate result = new SingleTargetRouter(SingleTargetRouter.SearchMode.VISIBILITY).route(request);

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail());
        assertEquals(RouteCandidate.Status.FOUND, result.getStatus());
        assertEquals(2 * Math.hypot(10, 5), result.getGeometry().getLength(), 1e-9);
        assertTrue(Arrays.stream(result.getGeometry().getCoordinates())
                .anyMatch(vertex -> vertex.distance(via.getCoordinate()) < 1e-9));
    }

    @Test
    void multiTargetLocalSnapshotPreservesTheMandatoryCorridorPoint() throws Exception {
        Fixture fixture = fixture(Collections.emptyList(), point(20, 0));
        Point via = point(10, 5);
        RouteRequest request = new RouteRequest("single-via", fixture.target, fixture.attachment,
                fixture.inputs, new Envelope(-5, 25, -10, 10), 100, via);

        MultiTargetRouteResult result = new MultiTargetRouter().route(
                Collections.singletonList(Collections.singletonList(request)), fixture.inputs, 16);

        assertFalse(result.getCandidates().isEmpty(), result::getDetail);
        assertTrue(result.getCandidates().stream().anyMatch(candidate -> candidate.getVariant().getSegments()
                .stream().anyMatch(segment -> Arrays.stream(segment.getGeometry().getCoordinates())
                        .anyMatch(vertex -> vertex.distance(via.getCoordinate()) < 1e-9))));
    }

    @Test
    void skipsEdgeAreaChecksOnlyForConvexAreasThatContainBothEndpoints() throws Exception {
        Geometry convex = new WKTReader(FACTORY).read("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))");
        Point convexStart = point(1, 1);
        Point convexGoal = point(9, 9);
        assertTrue(SingleTargetRouter.canSkipAreaEdgeCoverage(convex, convexStart, convexGoal));

        Geometry concave = new WKTReader(FACTORY).read("POLYGON ((0 0, 10 0, 10 4, 4 4, 4 10, 0 10, 0 0))");
        Point concaveStart = point(2, 8);
        Point concaveGoal = point(8, 2);
        assertTrue(concave.covers(concaveStart));
        assertTrue(concave.covers(concaveGoal));
        assertFalse(concave.covers(FACTORY.createLineString(new Coordinate[] {
                concaveStart.getCoordinate(), concaveGoal.getCoordinate()
        })));
        assertFalse(SingleTargetRouter.canSkipAreaEdgeCoverage(concave, concaveStart, concaveGoal));
    }

    @Test
    void increasesDiameterOnlyWhenTheMeasuredPathExceedsTheCurrentLimit() throws Exception {
        Fixture fixture = fixture(Collections.emptyList(), point(200, 0));
        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 205, -10, 10), 200));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertEquals(200.0, result.getGeometry().getLength(), 1e-9);
        assertEquals(65, result.getVariant().getSegments().get(0).getDiameterMm());
        assertTrue(result.getValidation().isAcceptable());
    }

    @Test
    void terminatesAtAnAvailableExistingChamberAndValidatesItsInputLink() throws Exception {
        Fixture fixture = fixture(Collections.emptyList(), point(20, 0));
        TypedId chamberId = TypedId.of("chamber-1");
        FeatureProperties chamberProperties = new FeatureProperties(chamberId, "heat_chamber");
        FeatureIndex.IndexedFeature chamber = indexed(
                new InputFeature(chamberProperties, NullNode.getInstance()), point(20, 0));
        fixture.inputs.add(chamber);
        fixture.attachment = new AttachmentCandidate(fixture.networkId, 1.0, point(20, 0), point(20, 0),
                AttachmentCandidate.EndpointKind.EXISTING_CHAMBER, chamberId,
                Collections.singletonList(fixture.networkId), 2, 500);

        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 25, -10, 10), 200));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertEquals("heat_chamber", result.getVariant().getNodes().get(1).getType());
        assertEquals(chamberId, result.getVariant().getNodes().get(1).getInputFeatureId());
        assertTrue(result.getValidation().isAcceptable());
    }

    @Test
    void routesAroundForbiddenPolygonWithoutBreakingNinetyDegreeTurnLimit() throws Exception {
        FeatureIndex.IndexedFeature park = restriction("park", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null);
        Fixture fixture = fixture(Collections.singletonList(park), point(20, 0));
        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 25, -10, 10), 200));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertTrue(result.getGeometry().getLength() > 20.0);
        assertTrue(result.getGeometry().distance(park.getGeometry()) >= 1.2 - 1e-8);
        assertTrue(result.getValidation().isAcceptable());
    }

    @Test
    void sparseNeighborRanksKeepTheDirectPairAndExpandByDistance() {
        List<Coordinate> vertices = Arrays.asList(new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(1, 0), new Coordinate(2, 0), new Coordinate(3, 0));

        Map<Long, Integer> ranks = SingleTargetRouter.sparseNeighborRanks(vertices);

        assertEquals(0, ranks.get(1L).intValue(), "start-goal pair is always considered");
        assertEquals(0, ranks.get(2L).intValue(), "nearest eastern neighbor enters at density two");
        assertTrue(ranks.get(4L) >= 2, "farther eastern neighbor waits for density four");
    }

    @Test
    void spatialFallbackCanRouteAroundParkAfterSmallVertexLimit() throws Exception {
        FeatureIndex.IndexedFeature park = restriction("park", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null);
        Fixture fixture = fixture(Collections.singletonList(park), point(20, 0));

        RouteCandidate result = new SingleTargetRouter(SingleTargetRouter.SearchMode.CONSERVATIVE_SPATIAL)
                .route(fixture.request(new Envelope(-5, 25, -10, 10), 6));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertTrue(result.getValidation().isAcceptable());
        assertEquals(RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH, result.getStatus());
    }

    @Test
    void spatialFallbackKeepsAnUnfoundSparseGraphIncomplete() throws Exception {
        FeatureIndex.IndexedFeature wall = restriction("wall", "park",
                "POLYGON ((8 -20, 12 -20, 12 20, 8 20, 8 -20))", null);
        Fixture fixture = fixture(Collections.singletonList(wall), point(20, 0));

        RouteCandidate result = new SingleTargetRouter(SingleTargetRouter.SearchMode.CONSERVATIVE_SPATIAL)
                .route(fixture.request(new Envelope(-5, 25, -10, 10), 6));

        assertEquals(RouteCandidate.Status.SEARCH_INCOMPLETE, result.getStatus(), result.getDetail());
    }

    @Test
    void spatialFallbackReportsExhaustedExactEdgeBudget() throws Exception {
        FeatureIndex.IndexedFeature park = restriction("park", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null);
        Fixture fixture = fixture(Collections.singletonList(park), point(20, 0));

        RouteCandidate result = new SingleTargetRouter(SingleTargetRouter.SearchMode.CONSERVATIVE_SPATIAL, 1)
                .route(fixture.request(new Envelope(-5, 25, -10, 10), 6));

        assertEquals(RouteCandidate.Status.SEARCH_INCOMPLETE, result.getStatus(), result.getDetail());
        assertTrue(result.getDetail().contains("budget exhausted"), result.getDetail());
    }

    @Test
    void navigatorSearchesBeyondThreeHundredPreparedVertices() throws Exception {
        List<FeatureIndex.IndexedFeature> obstacles = new ArrayList<>();
        obstacles.add(restriction("blocking-park", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null));
        for (int index = 0; index < 80; index++) {
            int x = 15 + index;
            obstacles.add(restriction("side-park-" + index, "park",
                    "POLYGON ((" + x + " 8, " + (x + 1) + " 8, " + (x + 1)
                            + " 9, " + x + " 9, " + x + " 8))", null));
        }
        Fixture fixture = fixture(obstacles, point(100, 0));

        RouteCandidate result = new SingleTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR)
                .route(fixture.request(new Envelope(-5, 105, -20, 20), 300));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail());
        assertEquals(RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH, result.getStatus());
        assertTrue(result.getValidation().isAcceptable());

        RouteCandidate limited = new SingleTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR, 1)
                .route(fixture.request(new Envelope(-5, 105, -20, 20), 300));
        assertEquals(RouteCandidate.Status.SEARCH_INCOMPLETE, limited.getStatus());
        assertTrue(limited.getDetail().contains("budget exhausted"), limited.getDetail());
    }

    @Test
    void navigatorCanSearchBeyondTheOlderSparseVertexBudget() throws Exception {
        List<FeatureIndex.IndexedFeature> obstacles = new ArrayList<>();
        obstacles.add(restriction("blocking-park", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null));
        for (int index = 0; index < 160; index++) {
            int x = 15 + index;
            obstacles.add(restriction("side-park-" + index, "park",
                    "POLYGON ((" + x + " 8, " + (x + 1) + " 8, " + (x + 1)
                            + " 9, " + x + " 9, " + x + " 8))", null));
        }
        Fixture fixture = fixture(obstacles, point(180, 0));
        Envelope area = new Envelope(-5, 185, -20, 20);

        RouteCandidate olderBudget = new SingleTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_SPATIAL)
                .route(fixture.request(area, 300));
        assertEquals(RouteCandidate.Status.SEARCH_INCOMPLETE, olderBudget.getStatus());
        assertTrue(olderBudget.getDetail().contains("1200"), olderBudget.getDetail());

        RouteCandidate navigator = new SingleTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR)
                .route(fixture.request(area, 300));
        assertTrue(navigator.isFound(), () -> navigator.getStatus() + ": " + navigator.getDetail());
        assertEquals(RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH, navigator.getStatus());
        assertTrue(navigator.getValidation().isAcceptable());

        RouteCandidate onDemand = new SingleTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR, 10000)
                .route(fixture.request(area, 300));
        assertTrue(onDemand.isFound(), () -> onDemand.getStatus() + ": " + onDemand.getDetail());
        assertTrue(onDemand.getValidation().isAcceptable());
    }

    @Test
    void conservativeNavigationBufferCoversTheExactObstacleWithFewerVertices() throws Exception {
        Geometry park = geometry("POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))");
        Geometry exact = park.buffer(1.21, 8);
        Geometry coarse = SingleTargetRouter.conservativeNavigationBuffer(
                park, exact, 1.21, new ObstacleBufferCache());

        assertNotNull(coarse);
        assertTrue(coarse.covers(exact));
        assertTrue(coarse.getNumPoints() < exact.getNumPoints());
    }

    @Test
    void conservativeForestModeKeepsExactValidationAndReportsIncompleteSearch() throws Exception {
        FeatureIndex.IndexedFeature park = restriction("park", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null);
        Fixture fixture = fixture(Collections.singletonList(park), point(20, 0));

        RouteCandidate result = new SingleTargetRouter(SingleTargetRouter.SearchMode.CONSERVATIVE_FOREST)
                .route(fixture.request(new Envelope(-5, 25, -10, 10), 30));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail());
        assertEquals(RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH, result.getStatus());
        assertTrue(result.getValidation().isAcceptable());
        assertTrue(result.getGeometry().distance(park.getGeometry()) >= 1.2 - 1e-8);
    }

    @Test
    void adaptiveModeFindsAndIndependentlyValidatesANarrowPassage() throws Exception {
        FeatureIndex.IndexedFeature lowerWall = restriction("lower-wall", "park",
                "POLYGON ((8 -20, 12 -20, 12 -2, 8 -2, 8 -20))", null);
        FeatureIndex.IndexedFeature upperWall = restriction("upper-wall", "park",
                "POLYGON ((8 2, 12 2, 12 20, 8 20, 8 2))", null);
        Fixture fixture = fixture(Arrays.asList(lowerWall, upperWall), point(20, 0));

        RouteCandidate result = new SingleTargetRouter(SingleTargetRouter.SearchMode.ADAPTIVE_QUADTREE)
                .route(fixture.request(new Envelope(-5, 25, -10, 10), 200));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertTrue(result.getGeometry().distance(lowerWall.getGeometry()) >= 1.2 - 1e-8);
        assertTrue(result.getGeometry().distance(upperWall.getGeometry()) >= 1.2 - 1e-8);
        assertTrue(result.getValidation().isAcceptable());
    }

    @Test
    void adaptiveModeBuildsAStraightSpecialRoadPassageAndValidatesIt() throws Exception {
        FeatureIndex.IndexedFeature road = restriction("adaptive-road", "road",
                "POLYGON ((8 -5, 12 -5, 12 5, 8 5, 8 -5))", null);
        Fixture fixture = fixture(Collections.singletonList(road), point(20, 0));

        RouteCandidate result = new SingleTargetRouter(SingleTargetRouter.SearchMode.ADAPTIVE_QUADTREE)
                .route(fixture.request(new Envelope(-5, 25, -10, 10), 300));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertTrue(result.getVariant().getSegments().stream().anyMatch(segment -> "special".equals(segment.getLayingMethod())));
        assertTrue(result.getValidation().isAcceptable());
    }

    @Test
    void routesOutOfItsOwnBuildingOnOneStraightFinalApproach() throws Exception {
        FeatureIndex.IndexedFeature ownOks = restriction("own", "oks",
                "POLYGON ((0 -3, 5 -3, 5 3, 0 3, 0 -3))", null);
        Fixture fixture = fixture(Collections.singletonList(ownOks), point(20, 0), point(4, 0));
        RouteCandidate result = router.route(fixture.request(new Envelope(-2, 25, -10, 10), 300));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertTrue(result.getValidation().isAcceptable(), () -> result.getValidation().getIssues().toString()
                + " notRun=" + result.getValidation().getChecksNotRun());
        assertEquals(4.0, result.getGeometry().getStartPoint().getX(), 1e-9);
        assertEquals(16.0, result.getGeometry().getLength(), 1e-9);
    }

    @Test
    void routesBothTypedTargetsInDemonstrationBuilding() throws Exception {
        FeatureIndex.IndexedFeature building = restriction("building-A", "oks",
                "POLYGON ((412138.329497534 6173554.5242459895,"
                + "412169.75128601096 6173553.890879906,"
                + "412170.6483500037 6173598.402787211,"
                + "412139.2268825378 6173599.03614984,"
                + "412138.329497534 6173554.5242459895))", null);
        TypedId networkId = TypedId.of("network-1");
        LineString networkLine = line("LINESTRING (412013.0917020078 6173579.315921061,"
                + "412075.93494087004 6173578.047606101)");
        FeatureIndex.IndexedFeature network = indexed(new InputFeature(
                properties(networkId, "heat_network", "diameter", 100), NullNode.getInstance()), networkLine);
        FeatureIndex.IndexedFeature source = indexed(new InputFeature(new FeatureProperties(
                TypedId.of("source-A"), "source"), NullNode.getInstance()),
                point(412013.0917020078, 6173579.315921061));
        FeatureIndex.IndexedFeature chamber = indexed(new InputFeature(new FeatureProperties(
                TypedId.of("chamber-A"), "heat_chamber"), NullNode.getInstance()),
                point(412044.5133202177, 6173578.681650277));
        ExistingHeatNetwork existingNetwork = new ExistingHeatNetwork(networkId, networkLine, 100);
        Envelope area = Wgs84BoundingBox.fromArray(new double[] {37.5894213, 55.6962667,
                37.6130784, 55.7037093}).toProjectedEnvelope(new CrsTransform());
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(Arrays.asList(network, building, source, chamber));
        List<List<RouteRequest>> requests = new ArrayList<>();
        List<Variant> singleVariants = new ArrayList<>();
        List<AttachmentCandidate> attachments = new ArrayList<>();

        for (boolean numeric : new boolean[] {true, false}) {
            TypedId targetId = numeric ? TypedId.of(BigDecimal.ONE) : TypedId.of("1");
            Point location = numeric ? point(412148.2046776306, 6173576.5901644705)
                    : point(412160.7733288189, 6173576.336818728);
            FeatureIndex.IndexedFeature target = indexed(new InputFeature(properties(targetId,
                    "oks_connection_point", "flow_tph", numeric ? 2.0 : 1.5),
                    NullNode.getInstance()), location);
            AttachmentCandidate attachment = new ExistingNetworkConnector().findCandidates(location,
                    Collections.singletonList(existingNetwork), Collections.emptyList()).getCandidates().get(0);
            RouteRequest request = new RouteRequest("sample-" + numeric, target, attachment,
                    Arrays.asList(target, network, building, source, chamber), area, 300);
            inputs.add(target);
            requests.add(Collections.singletonList(request));
            RouteCandidate result = router.route(request);
            assertTrue(result.isFound(), () -> "target=" + targetId + " numeric=" + targetId.isNumeric()
                    + " status=" + result.getStatus() + " detail=" + result.getDetail()
                    + (result.getValidation() == null ? "" : " issues=" + result.getValidation().getIssues()));
            singleVariants.add(result.getVariant());
            attachments.add(attachment);
        }
        RouteTreeOptimizer.OptimizationResult optimized = new RouteTreeOptimizer().optimize(
                "sample-combined", singleVariants, attachments, inputs);
        assertTrue(optimized.isFound(), () -> "optimization=" + optimized.getDetail()
                + (optimized.getValidation() == null ? "" : " issues=" + optimized.getValidation().getIssues()
                        .stream().map(issue -> issue.getCode() + ":" + issue.getElementId() + ":"
                                + issue.getMessage()).collect(java.util.stream.Collectors.toList())));
        MultiTargetRouteResult combined = new MultiTargetRouter().route(requests, inputs, 64);
        assertEquals(MultiTargetRouteResult.Status.PARTIAL, combined.getStatus(),
                () -> combined.getDetail() + " attempts=" + combined.getTargetAttempts());
        assertTrue(combined.getUnroutedTargetIds().isEmpty());
        assertTrue(combined.getUnsearchedTargetIds().isEmpty());
        assertTrue(combined.getCandidates().stream().anyMatch(candidate -> candidate.getVariant().getNodes()
                .stream().filter(node -> "oks_connection_point".equals(node.getType())).count() == 2));
        MultiTargetRouteResult conservative = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_FOREST).route(requests, inputs, 64);
        assertTrue(conservative.getCandidates().stream().anyMatch(candidate -> candidate.getVariant().getNodes()
                .stream().filter(node -> "oks_connection_point".equals(node.getType())).count() == 2),
                () -> conservative.getStatus() + ": " + conservative.getDetail());
    }

    @Test
    void buildsAndValidatesASpecialRoadPassage() throws Exception {
        FeatureIndex.IndexedFeature road = restriction("road", "road",
                "POLYGON ((8 -5, 12 -5, 12 5, 8 5, 8 -5))", null);
        Fixture fixture = fixture(Collections.singletonList(road), point(20, 0));
        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 25, -10, 10), 300));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertTrue(result.getVariant().getSegments().stream().anyMatch(segment -> "special".equals(segment.getLayingMethod())));
        assertTrue(result.getValidation().isAcceptable());
    }

    @Test
    void reportsSearchExhaustionInsideConfiguredAreaWithoutCallingItImpossible() throws Exception {
        FeatureIndex.IndexedFeature wall = restriction("wall", "park",
                "POLYGON ((8 -20, 12 -20, 12 20, 8 20, 8 -20))", null);
        Fixture fixture = fixture(Collections.singletonList(wall), point(20, 0));
        RouteCandidate result = router.route(fixture.request(new Envelope(-1, 21, -10, 10), 200));

        assertEquals(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA, result.getStatus());
        assertFalse(result.isFound());
        assertTrue(result.getDetail().contains("inside the configured search area"));
    }

    @Test
    void reportsWhenVisibilityCandidateLimitIsReached() throws Exception {
        FeatureIndex.IndexedFeature park = restriction("park", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null);
        Fixture fixture = fixture(Collections.singletonList(park), point(20, 0));
        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 25, -10, 10), 2));

        assertEquals(RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED, result.getStatus());
        assertTrue(result.getDetail().contains("configured limit is 2"));
    }

    @Test
    void doesNotTreatAValidationRejectedGeometryAsAFinalRoute() throws Exception {
        ValidationReport rejectedValidation = new ValidationReport(Collections.emptyList(),
                Collections.singletonList("spatial clearance"));
        RouteCandidate rejected = RouteCandidate.rejected(line("LINESTRING (0 0, 1 0)"), null,
                rejectedValidation);

        assertEquals(RouteCandidate.Status.VALIDATION_REJECTED, rejected.getStatus());
        assertNotNull(rejected.getGeometry());
        assertFalse(rejected.isFound());
    }

    @Test
    void ignoresComplexObstaclesOutsideTheLocalCorridorAndMarksLocalResultIncomplete() throws Exception {
        FeatureIndex.IndexedFeature park = restriction("park", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null);
        Coordinate[] ring = new Coordinate[401];
        for (int i = 0; i < 400; i++) {
            double angle = 2.0 * Math.PI * i / 400.0;
            ring[i] = new Coordinate(1000 + 30 * Math.cos(angle), 1000 + 30 * Math.sin(angle));
        }
        ring[400] = new Coordinate(ring[0]);
        FeatureProperties complexProperties = properties(TypedId.of("far-complex"), "restriction",
                "restriction_type", "park");
        FeatureIndex.IndexedFeature farComplex = indexed(
                new InputFeature(complexProperties, NullNode.getInstance()), FACTORY.createPolygon(ring));
        Fixture fixture = fixture(Arrays.asList(park, farComplex), point(20, 0));

        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 1200, -1100, 1100), 100));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail()
                + (result.getValidation() == null ? "" : result.getValidation().getIssues().toString()));
        assertEquals(RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH, result.getStatus());
        assertTrue(result.getGeometry().getLength() > 20.0);
        assertTrue(result.getValidation().isAcceptable());
    }

    @Test
    void countsUniqueVisibilityVerticesAcrossOverlappingObstacleBoundaries() throws Exception {
        FeatureIndex.IndexedFeature firstPark = restriction("park-a", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null);
        FeatureIndex.IndexedFeature duplicatePark = restriction("park-b", "park",
                "POLYGON ((8 -3, 12 -3, 12 3, 8 3, 8 -3))", null);
        Fixture fixture = fixture(Arrays.asList(firstPark, duplicatePark), point(20, 0));

        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 25, -10, 10), 100));

        assertTrue(result.isFound(), () -> result.getStatus() + ": " + result.getDetail());
        assertTrue(result.getValidation().isAcceptable());
    }

    @Test
    void clippedObstacleBoundariesExposeIntersectionNodesOnTheCorridorEdge() throws Exception {
        Geometry corridor = FACTORY.toGeometry(new Envelope(-5, 25, -25, 25));
        Geometry wall = geometry("POLYGON ((8 -40, 12 -40, 12 40, 8 40, 8 -40))")
                .buffer(0.2, 8);

        List<Coordinate> clipped = SingleTargetRouter.clippedBoundaryCoordinates(wall, corridor);

        assertTrue(clipped.stream().anyMatch(point -> Math.abs(point.x - 7.8) < 1e-6
                && Math.abs(point.y + 25.0) < 1e-6));
        assertTrue(clipped.stream().anyMatch(point -> Math.abs(point.x - 12.2) < 1e-6
                && Math.abs(point.y - 25.0) < 1e-6));
    }

    @Test
    void rejectsARequestWithoutItsSelectedAttachmentNetwork() throws Exception {
        Fixture fixture = fixture(Collections.emptyList(), point(20, 0));
        fixture.inputs.removeIf(input -> input.getFeature().getId().equals(fixture.networkId));
        RouteCandidate result = router.route(fixture.request(new Envelope(-5, 25, -10, 10), 100));

        assertEquals(RouteCandidate.Status.INVALID_REQUEST, result.getStatus());
        assertFalse(result.isFound());
    }

    private static String validationDetails(RouteCandidate result) {
        StringBuilder details = new StringBuilder();
        result.getValidation().getIssues().forEach(issue -> details.append(issue.getCode()).append(':')
                .append(issue.getElementId()).append(':').append(issue.getMessage()).append(';'));
        return details.toString();
    }

    private static Fixture fixture(List<FeatureIndex.IndexedFeature> extra, Point networkPoint) throws Exception {
        return fixture(extra, networkPoint, point(0, 0));
    }

    private static Fixture fixture(List<FeatureIndex.IndexedFeature> extra, Point networkPoint, Point targetPoint)
            throws Exception {
        TypedId targetId = TypedId.of("target-1");
        TypedId networkId = TypedId.of("network-1");
        InputFeature targetInput = new InputFeature(properties(targetId, "oks_connection_point", "flow_tph", 3.0),
                NullNode.getInstance());
        FeatureIndex.IndexedFeature target = indexed(targetInput, targetPoint);
        InputFeature networkInput = new InputFeature(properties(networkId, "heat_network", "diameter", 500),
                NullNode.getInstance());
        FeatureIndex.IndexedFeature network = indexed(networkInput, line("LINESTRING ("
                + networkPoint.getX() + " " + (networkPoint.getY() - 1) + ", "
                + networkPoint.getX() + " " + (networkPoint.getY() + 1) + ")"));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        inputs.add(target);
        inputs.add(network);
        inputs.addAll(extra);
        AttachmentCandidate attachment = new AttachmentCandidate(networkId, 1.0, networkPoint, networkPoint,
                AttachmentCandidate.EndpointKind.NEW_CHAMBER, null, Collections.singletonList(networkId), 2, 500);
        return new Fixture(target, networkId, attachment, inputs);
    }

    private static FeatureIndex.IndexedFeature restriction(String id, String type, String wkt, Double width)
            throws Exception {
        FeatureProperties properties = properties(TypedId.of(id), "restriction", "restriction_type", type);
        if (width != null) properties.putExtra("width_m", MAPPER.valueToTree(width));
        return indexed(new InputFeature(properties, NullNode.getInstance()), geometry(wkt));
    }

    private static FeatureProperties properties(TypedId id, String type, String name, Object value) {
        FeatureProperties properties = new FeatureProperties(id, type);
        properties.putExtra(name, MAPPER.valueToTree(value));
        return properties;
    }

    private static FeatureIndex.IndexedFeature indexed(InputFeature feature, Geometry geometry) {
        return new FeatureIndex.IndexedFeature(feature, geometry);
    }

    private static LineString line(String wkt) throws Exception { return (LineString) geometry(wkt); }
    private static Point point(double x, double y) { return FACTORY.createPoint(new org.locationtech.jts.geom.Coordinate(x, y)); }
    private static Geometry geometry(String wkt) throws Exception { return new WKTReader(FACTORY).read(wkt); }

    private static final class Fixture {
        private final FeatureIndex.IndexedFeature target;
        private final TypedId networkId;
        private AttachmentCandidate attachment;
        private final List<FeatureIndex.IndexedFeature> inputs;
        private Fixture(FeatureIndex.IndexedFeature target, TypedId networkId, AttachmentCandidate attachment,
                List<FeatureIndex.IndexedFeature> inputs) {
            this.target = target;
            this.networkId = networkId;
            this.attachment = attachment;
            this.inputs = inputs;
        }
        private RouteRequest request(Envelope area, int maximumVertices) {
            return new RouteRequest("single", target, attachment, inputs, area, maximumVertices);
        }
    }
}
