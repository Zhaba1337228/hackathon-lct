package ru.lct.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnetwork.costing.VariantCostCalculator;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.network.RouteTreeOptimizer;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.spatial.ClearanceCheck;
import ru.lct.heatnetwork.spatial.FeatureIndex;

class MultiTargetRouterTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MultiTargetRouter router = new MultiTargetRouter();

    @Test
    void grownCandidateDoesNotEraseReasonsFromItsParent() {
        TypedId attachedOnlyInChild = TypedId.of("later-building");
        TypedId stillMissing = TypedId.of("another-building");
        Map<TypedId, String> parentReasons = new LinkedHashMap<>();
        parentReasons.put(attachedOnlyInChild, "GROUP_MERGE_NOT_EXPLORED");
        parentReasons.put(stillMissing, "SEARCH_INCOMPLETE");

        Map<TypedId, String> childReasons = MultiTargetRouter.reasonsForGrownCandidate(
                parentReasons, Collections.singleton(attachedOnlyInChild));

        assertEquals("GROUP_MERGE_NOT_EXPLORED", parentReasons.get(attachedOnlyInChild));
        assertEquals("SEARCH_INCOMPLETE", parentReasons.get(stillMissing));
        assertTrue(!childReasons.containsKey(attachedOnlyInChild));
        assertEquals("SEARCH_INCOMPLETE", childReasons.get(stillMissing));
    }

    @Test
    void groupContinuationKeepsStrongTrunkProductiveGraftAndComplementaryForest() {
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        for (int id = 1; id <= 17; id++) {
            TypedId targetId = TypedId.of(BigDecimal.valueOf(id));
            TypedId networkId = TypedId.of("network-" + id);
            TypedId chamberId = TypedId.of("chamber-" + id);
            int x = id * 30;
            inputs.add(target(targetId, 1.0, point(x, 0)));
            inputs.add(indexed(properties(networkId, "heat_network", "diameter", 500),
                    FACTORY.createLineString(new Coordinate[] {new Coordinate(x + 5, 0),
                            new Coordinate(x + 10, 0)})));
            inputs.add(indexed(new FeatureProperties(chamberId, "heat_chamber"), point(x + 5, 0)));
        }
        RouteTreeOptimizer.TreeCandidate strong = forest("strong", inputs,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        RouteTreeOptimizer.TreeCandidate subsetA = forest("subset-a", inputs,
                1, 2, 3, 4, 5, 6, 7, 8);
        RouteTreeOptimizer.TreeCandidate subsetB = forest("subset-b", inputs,
                1, 2, 3, 4, 5, 6, 7, 9);
        RouteTreeOptimizer.TreeCandidate west = forest("west", inputs,
                1, 2, 3, 4, 5, 6, 12, 13);
        RouteTreeOptimizer.TreeCandidate east = forest("east", inputs,
                1, 2, 3, 4, 14, 16);

        List<RouteTreeOptimizer.TreeCandidate> continued = MultiTargetRouter.selectGroupContinuations(
                Collections.singletonList(strong), Arrays.asList(subsetA, subsetB),
                Arrays.asList(west, east), inputs);

        assertEquals(3, continued.size());
        assertEquals(strong, continued.get(0), "validated eleven-target trunk must keep its slot");
        assertTrue(continued.get(1) == subsetA || continued.get(1) == subsetB,
                "a validated graft may be a target subset yet still have productive geometry");
        assertTrue(continued.contains(west));
        assertEquals(Arrays.asList(80, 160, 80), MultiTargetRouter.groupContinuationBudgets(
                continued, Arrays.asList(subsetA, subsetB), 320));
        assertEquals(Arrays.asList(1, 1, 1), MultiTargetRouter.groupContinuationBudgets(
                continued, Arrays.asList(subsetA, subsetB), 3));
        assertEquals(Collections.singletonList(200), MultiTargetRouter.groupContinuationBudgets(
                Collections.singletonList(subsetA), Collections.singletonList(subsetA), 200));

        List<RouteTreeOptimizer.TreeCandidate> withoutGraft = MultiTargetRouter.selectGroupContinuations(
                Collections.singletonList(strong), Collections.emptyList(),
                Arrays.asList(west, east), inputs);
        assertEquals(strong, withoutGraft.get(0));
        assertTrue(withoutGraft.contains(west) && withoutGraft.contains(east));
        assertEquals(Arrays.asList(107, 107, 106), MultiTargetRouter.groupContinuationBudgets(
                withoutGraft, Collections.emptyList(), 320));
    }

    @Test
    void keepsDistinctTargetIdsAndReturnsPartialTreeWithoutDroppingRoutableTargets() throws Exception {
        Fixture fixture = fixture(true);
        MultiTargetRouteResult result = router.route(fixture.requests, fixture.inputs, 20);

        assertEquals(MultiTargetRouteResult.Status.PARTIAL, result.getStatus(), result.getDetail()
                + " attempts=" + result.getTargetAttempts() + " unrouted=" + result.getUnroutedTargetIds());
        assertEquals(Collections.singletonList(fixture.blockedTargetId), result.getUnroutedTargetIds());
        assertEquals(1, result.getCandidates().size());
        assertEquals(2, result.getCandidates().get(0).getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(2, result.getCandidates().get(0).getVariant().getNodes().stream()
                .filter(node -> "heat_chamber".equals(node.getType())
                        && node.getId().startsWith("attachment:" )).count());
        assertTrue(result.getCandidates().get(0).getValidation().isAcceptable());
    }

    @Test
    void connectsBothTargetsWhenEverySearchFindsAValidCandidate() throws Exception {
        Fixture fixture = fixture(false);
        MultiTargetRouteResult result = router.route(fixture.requests, fixture.inputs, 20);

        assertEquals(MultiTargetRouteResult.Status.COMPLETE, result.getStatus(), result.getDetail()
                + " attempts=" + result.getTargetAttempts() + " unrouted=" + result.getUnroutedTargetIds());
        assertEquals(2, result.getCandidates().get(0).getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(2, result.getCandidates().get(0).getVariant().getSegments().size());
        assertTrue(result.getCandidates().get(0).getValidation().isAcceptable());
    }

    @Test
    void exhaustiveFoundRouteModeChecksEveryNonemptySubsetWithinBudget() throws Exception {
        Fixture fixture = fixture(false);
        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_EXHAUSTIVE)
                .route(fixture.requests, fixture.inputs, 3);

        assertEquals(MultiTargetRouteResult.Status.COMPLETE, result.getStatus(), result.getDetail());
        assertEquals(3, result.getCombinationsEvaluated(), "two single routes have three nonempty subsets");
        assertTrue(!result.isCombinationLimitReached());
        assertEquals(2, result.getCandidates().get(0).getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertTrue(result.getCandidates().stream().allMatch(candidate -> candidate.getValidation().isAcceptable()));
    }

    @Test
    void emitsValidatedRouteAttemptsAsTheyFinishAndIgnoresTelemetryFailures() throws Exception {
        Fixture fixture = fixture(false);
        List<String> completedTargetIds = Collections.synchronizedList(new ArrayList<>());
        int expectedAttempts = fixture.requests.stream().mapToInt(List::size).sum();

        MultiTargetRouteResult result = router.route(fixture.requests, fixture.inputs, 20, null, 1,
                (request, candidate) -> {
                    assertTrue(candidate.isFound());
                    assertTrue(candidate.getValidation().isAcceptable());
                    completedTargetIds.add(request.getTarget().getFeature().getId().toString());
                });

        assertEquals(MultiTargetRouteResult.Status.COMPLETE, result.getStatus());
        assertEquals(expectedAttempts, completedTargetIds.size());
        assertTrue(completedTargetIds.contains(fixture.requests.get(0).get(0).getTarget()
                .getFeature().getId().toString()));

        List<String> parallelCompletedTargetIds = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        MultiTargetRouteResult parallel;
        try {
            parallel = router.route(fixture.requests, fixture.inputs, 20, executor, 2,
                    (request, candidate) -> {
                        assertTrue(candidate.isFound());
                        assertTrue(candidate.getValidation().isAcceptable());
                        parallelCompletedTargetIds.add(request.getTarget().getFeature().getId().toString());
                    });
        } finally {
            executor.shutdownNow();
        }
        assertEquals(result.getStatus(), parallel.getStatus());
        assertEquals(expectedAttempts, parallelCompletedTargetIds.size(),
                "the sliding-window search must publish every settled attempt");

        MultiTargetRouteResult withoutTelemetry = router.route(fixture.requests, fixture.inputs, 20, null, 1,
                (request, candidate) -> { throw new IllegalStateException("progress channel unavailable"); });
        assertEquals(result.getStatus(), withoutTelemetry.getStatus());
        assertEquals(result.getCandidates().get(0).getTotalLengthM(),
                withoutTelemetry.getCandidates().get(0).getTotalLengthM(), 1e-6);
    }

    @Test
    void adaptiveForestKeepsTheFirstValidPrefixWhenAnotherFoundRouteCrossesIt() throws Exception {
        TypedId firstId = TypedId.of("crossing-target-a");
        TypedId secondId = TypedId.of("crossing-target-b");
        TypedId firstNetworkId = TypedId.of("crossing-network-a");
        TypedId secondNetworkId = TypedId.of("crossing-network-b");
        TypedId firstChamberId = TypedId.of("crossing-chamber-a");
        TypedId secondChamberId = TypedId.of("crossing-chamber-b");
        FeatureIndex.IndexedFeature firstTarget = target(firstId, 2.0, point(0, 0));
        FeatureIndex.IndexedFeature secondTarget = target(secondId, 3.0, point(5, -5));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(Arrays.asList(
                firstTarget, secondTarget,
                indexed(properties(firstNetworkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 -1, 10 1)")),
                indexed(properties(secondNetworkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (5 5, 5 10)")),
                indexed(new FeatureProperties(firstChamberId, "heat_chamber"), point(10, 0)),
                indexed(new FeatureProperties(secondChamberId, "heat_chamber"), point(5, 5))));
        List<List<RouteRequest>> requests = Arrays.asList(
                Collections.singletonList(request("crossing-a", firstTarget, inputs,
                        attachment(firstNetworkId, firstChamberId, point(10, 0)), new Envelope(-2, 12, -8, 12))),
                Collections.singletonList(request("crossing-b", secondTarget, inputs,
                        attachment(secondNetworkId, secondChamberId, point(5, 5)), new Envelope(-2, 12, -8, 12))));

        MultiTargetRouteResult result = new MultiTargetRouter(SingleTargetRouter.SearchMode.ADAPTIVE_QUADTREE)
                .route(requests, inputs, 20);

        assertEquals(MultiTargetRouteResult.Status.PARTIAL, result.getStatus(), result.getDetail());
        assertTrue(result.getCandidates().size() >= 1, "the accepted prefix must survive a rejected crossing branch");
        assertEquals(1, result.getCandidates().get(0).getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertTrue(result.getCandidates().get(0).getValidation().isAcceptable());
        assertEquals(1, result.getUnroutedTargetIds().size(), "the omitted target must be reported for the best forest");
    }

    @Test
    void directBranchModeConnectsCrossingTargetThroughNewTrunkChamber() throws Exception {
        TypedId firstId = TypedId.of("crossing-target-a");
        TypedId secondId = TypedId.of("crossing-target-b");
        TypedId firstNetworkId = TypedId.of("crossing-network-a");
        TypedId secondNetworkId = TypedId.of("crossing-network-b");
        TypedId firstChamberId = TypedId.of("crossing-chamber-a");
        TypedId secondChamberId = TypedId.of("crossing-chamber-b");
        FeatureIndex.IndexedFeature firstTarget = target(firstId, 2.0, point(0, 0));
        FeatureIndex.IndexedFeature secondTarget = target(secondId, 3.0, point(5, -5));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(Arrays.asList(
                firstTarget, secondTarget,
                indexed(properties(firstNetworkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 -1, 10 1)")),
                indexed(properties(secondNetworkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (5 5, 5 10)")),
                indexed(new FeatureProperties(firstChamberId, "heat_chamber"), point(10, 0)),
                indexed(new FeatureProperties(secondChamberId, "heat_chamber"), point(5, 5))));
        List<List<RouteRequest>> requests = Arrays.asList(
                Collections.singletonList(request("crossing-a", firstTarget, inputs,
                        attachment(firstNetworkId, firstChamberId, point(10, 0)), new Envelope(-2, 12, -8, 12))),
                Collections.singletonList(request("crossing-b", secondTarget, inputs,
                        attachment(secondNetworkId, secondChamberId, point(5, 5)), new Envelope(-2, 12, -8, 12))));

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_DIRECT_BRANCH).route(requests, inputs, 3);

        assertEquals(MultiTargetRouteResult.Status.COMPLETE, result.getStatus(), result.getDetail());
        assertEquals(3, result.getCombinationsEvaluated());
        assertTrue(result.getUnroutedTargetIds().isEmpty());
        assertEquals(2, result.getCandidates().get(0).getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertTrue(result.getCandidates().get(0).getValidation().isAcceptable());
    }

    @Test
    void navigatorGrowsToTargetWithoutStandaloneAttachmentRequest() throws Exception {
        TypedId firstId = TypedId.of("seed"), secondId = TypedId.of("branch");
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature first = target(firstId, 2.0, point(0, 0));
        FeatureIndex.IndexedFeature second = target(secondId, 3.0, point(5, 5));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(first, second,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)),
                indexed(properties(TypedId.of("park"), "restriction", "restriction_type", "park"),
                        geometry("POLYGON ((4.5 2, 5.5 2, 5.5 3, 4.5 3, 4.5 2))")));
        Envelope area = new Envelope(-5, 35, -12, 12);
        List<List<RouteRequest>> seedRequests = Collections.singletonList(Collections.singletonList(
                request("seed-route", first, inputs, attachment(networkId, chamberId, point(10, 0)), area)));

        List<String> trajectoryIds = new ArrayList<>();
        List<Variant> treeSnapshots = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                trajectoryIds.add(trajectoryId);
                treeSnapshots.add(checkedTree);
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        seedRequests, inputs, 3, null, 1, progress,
                        Arrays.asList(first, second), area, 300);

        assertTrue(result.getCandidates().stream().anyMatch(candidate ->
                candidate.getVariant().getNodes().stream().filter(node ->
                        "oks_connection_point".equals(node.getType())).count() == 2
                        && candidate.getValidation().isAcceptable()), result.getDetail());
        assertTrue(result.getTargetAttempts().get(secondId)
                .contains(RouteCandidate.Status.TREE_BRANCH_ACCEPTED));
        assertTrue(trajectoryIds.contains("first_valid:seed0"));
        assertTrue(trajectoryIds.contains("shortest_valid:seed0"));
        assertTrue(treeSnapshots.stream().anyMatch(tree -> tree.getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count() == 1));
        assertTrue(treeSnapshots.stream().anyMatch(tree -> tree.getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count() == 2
                && tree.getSegments().size() > 1));
    }

    @Test
    void navigatorKeepsUnsearchedReasonsWithTheCandidateThatProducedThem() throws Exception {
        TypedId seedId = TypedId.of("seed"), branchId = TypedId.of("branch");
        TypedId outsideId = TypedId.of("outside"), networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(seedId, 2.0, point(0, 0));
        FeatureIndex.IndexedFeature branch = target(branchId, 3.0, point(5, 5));
        FeatureIndex.IndexedFeature outside = target(outsideId, 4.0, point(5, 30));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, branch, outside,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)));
        Envelope area = new Envelope(-5, 35, -12, 12);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(10, 0)), area)));

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, RouteProgressListener.NONE,
                        Arrays.asList(seed, branch, outside), area, 300);

        String baseId = result.getCandidates().stream().filter(candidate ->
                candidate.getVariant().getNodes().stream().filter(node ->
                        "oks_connection_point".equals(node.getType())).count() == 1)
                .findFirst().get().getVariant().getId();
        String grownId = result.getCandidates().stream().filter(candidate ->
                candidate.getVariant().getNodes().stream().filter(node ->
                        "oks_connection_point".equals(node.getType())).count() == 2)
                .findFirst().get().getVariant().getId();
        assertTrue(result.hasCandidateDiagnostics(baseId));
        assertTrue(result.getUnsearchedReasonsForCandidate(baseId).isEmpty());
        assertEquals("OUTSIDE_SEARCH_AREA",
                result.getUnsearchedReasonsForCandidate(grownId).get(outsideId));
        assertTrue(result.getUnsearchedReasonsForCandidate(grownId).containsKey(outsideId));
        assertTrue(!result.getUnsearchedReasonsForCandidate(grownId).containsKey(branchId));
    }

    @Test
    void navigatorGivesEveryTargetAFirstVisitBeforeRepeatingBlockedGoals() throws Exception {
        TypedId seedId = TypedId.of("seed"), nearId = TypedId.of("a-near");
        TypedId farId = TypedId.of("z-far"), networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(seedId, 2.0, point(0, 0));
        FeatureIndex.IndexedFeature near = target(nearId, 2.0, point(5, 7));
        FeatureIndex.IndexedFeature far = target(farId, 2.0, point(5, -5));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, near, far,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)),
                indexed(properties(TypedId.of("separating-park"), "restriction", "restriction_type", "park"),
                        geometry("POLYGON ((-5 2, 35 2, 35 4, -5 4, -5 2))")));
        Envelope area = new Envelope(-5, 35, -12, 12);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(10, 0)), area)));
        List<TypedId> fairAttempts = new ArrayList<>();
        List<Variant> groupSnapshots = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                if (trajectoryId.startsWith("group:")) groupSnapshots.add(checkedTree);
            }
            @Override public void onTreeAttemptCompleted(TypedId targetId, String attemptId, Point destination,
                    RouteCandidate.Status status, String reason, Double lengthM) {
                if (attemptId.startsWith("navigator:shortest_valid:seed0")) fairAttempts.add(targetId);
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress,
                        Arrays.asList(seed, near, far), area, 300);

        int firstFar = fairAttempts.indexOf(farId);
        assertTrue(firstFar >= 0, "the distant target must receive a real attempt");
        assertTrue(fairAttempts.subList(0, firstFar).stream().filter(nearId::equals).count() <= 2,
                "a blocked nearby target must not spend a full window before the distant first visit");
        assertTrue(result.getTargetAttempts().get(farId).contains(RouteCandidate.Status.TREE_BRANCH_ACCEPTED),
                result.getDetail());
        RouteTreeOptimizer.TreeCandidate fallback = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:group:"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "a failed pair must not block a valid single branch from the same seed"));
        assertTrue(fallback.getValidation().isAcceptable());
        assertEquals(2, fallback.getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertTrue(fallback.getVariant().getNodes().stream()
                .anyMatch(node -> farId.equals(node.getInputFeatureId())));
        assertEquals(2, groupSnapshots.size(),
                "only the seed and a fully validated single continuation enter live preview");
    }

    @Test
    void navigatorProjectsTreeGoalsFromTheRequiredOwnBuildingExit() throws Exception {
        TypedId seedId = TypedId.of("seed"), targetId = TypedId.of("target");
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(seedId, 1.0, point(-5, -11));
        FeatureIndex.IndexedFeature target = target(targetId, 1.0, point(0, 0));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, target,
                indexed(properties(TypedId.of("own-building"), "restriction", "restriction_type", "oks"),
                        geometry("POLYGON ((-10 -5, 2 -5, 2 5, -10 5, -10 -5))")),
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (20 -11, 30 -11)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(20, -11)));
        Envelope area = new Envelope(-15, 35, -20, 10);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId,
                        point(20, -11)), area)));
        List<Point> searchedGoals = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeAttemptCompleted(TypedId completedTargetId, String attemptId,
                    Point destination, RouteCandidate.Status status, String reason, Double lengthM) {
                if (targetId.equals(completedTargetId)
                        && attemptId.startsWith("navigator:group:seed0:search:")) searchedGoals.add(destination);
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress,
                        Arrays.asList(seed, target), area, 300);

        assertTrue(!searchedGoals.isEmpty(), "the valid seed must try a new-tree attachment");
        assertTrue(searchedGoals.get(0).getX() > 6.5,
                "the first goal must be projected after the mandatory eastward own-building exit");
        assertTrue(result.getCandidates().stream().anyMatch(candidate ->
                candidate.getVariant().getId().startsWith("navigator:group:")
                        && candidate.getValidation().isAcceptable()
                        && candidate.getVariant().getNodes().stream().anyMatch(node ->
                                targetId.equals(node.getInputFeatureId()))),
                "the shifted goal should produce a fully validated short branch");
    }

    @Test
    void navigatorPublishesOnlyACompleteValidatedTwoTargetGroup() throws Exception {
        TypedId seedId = TypedId.of("seed"), firstId = TypedId.of("first");
        TypedId secondId = TypedId.of("second"), networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(seedId, 2.0, point(0, 0));
        FeatureIndex.IndexedFeature first = target(firstId, 2.0, point(5, 8));
        FeatureIndex.IndexedFeature second = target(secondId, 2.0, point(6, 10));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, first, second,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)));
        Envelope area = new Envelope(-5, 35, -12, 15);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(10, 0)), area)));
        List<Variant> groupSnapshots = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                if (trajectoryId.startsWith("group:")) groupSnapshots.add(checkedTree);
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress,
                        Arrays.asList(seed, first, second), area, 300);

        RouteTreeOptimizer.TreeCandidate grouped = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:group:"))
                .findFirst().orElseThrow(() -> new AssertionError("group pair was not published"));
        assertTrue(grouped.getValidation().isAcceptable());
        assertEquals(3, grouped.getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertTrue(grouped.getVariant().getNodes().stream()
                .filter(node -> "heat_chamber".equals(node.getType())).count() >= 3);
        assertTrue(grouped.getVariant().getSegments().stream()
                .anyMatch(segment -> segment.getFlowTph().compareTo(BigDecimal.valueOf(4)) >= 0));
        assertEquals(2, groupSnapshots.size(), "only the seed and complete pair may enter live preview");
        RouteTreeOptimizer.TreeCandidate portfolio = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:portfolio:"))
                .filter(candidate -> candidate.getVariant().getNodes().stream()
                        .filter(node -> "oks_connection_point".equals(node.getType())).count() == 3)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "a validated first branch must remain available for a distinct continuation"));
        assertTrue(portfolio.getValidation().isAcceptable());
        assertTrue(result.getUnsearchedReasonsForCandidate(portfolio.getVariant().getId()).isEmpty());
        VariantCostCalculator cost = new VariantCostCalculator();
        BigDecimal groupScore = cost.calculate(grouped.getVariant(), grouped.getValidation(),
                inputs, Collections.emptyList()).getSummary().getScore();
        BigDecimal bestSequentialScore = result.getCandidates().stream()
                .filter(candidate -> !candidate.getVariant().getId().startsWith("navigator:group:"))
                .filter(candidate -> !candidate.getVariant().getId().startsWith("navigator:portfolio:"))
                .filter(candidate -> candidate.getVariant().getNodes().stream()
                        .filter(node -> "oks_connection_point".equals(node.getType())).count() == 3)
                .map(candidate -> cost.calculate(candidate.getVariant(), candidate.getValidation(),
                        inputs, Collections.emptyList()).getSummary().getScore())
                .min(BigDecimal::compareTo).orElseThrow(() -> new AssertionError("sequential control missing"));
        assertTrue(groupScore.compareTo(bestSequentialScore) < 0,
                "group score=" + groupScore + ", sequential=" + bestSequentialScore);
    }

    @Test
    void navigatorLooksAheadToAThirdTargetOnTheNewDistributionTree() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 2.0, point(0, 0));
        FeatureIndex.IndexedFeature first = target(TypedId.of("first"), 2.0, point(5, 8));
        FeatureIndex.IndexedFeature second = target(TypedId.of("second"), 2.0, point(6, 10));
        FeatureIndex.IndexedFeature third = target(TypedId.of("third"), 2.0, point(7, 12));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, first, second, third,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)));
        Envelope area = new Envelope(-5, 35, -12, 18);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(10, 0)), area)));
        List<String> acceptedExplanations = new ArrayList<>();
        List<Point> thirdLookaheadGoals = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeAttemptCompleted(TypedId targetId, String attemptId,
                    Point destination, RouteCandidate.Status status, String reason, Double lengthM) {
                if (attemptId.startsWith("navigator:group:")
                        && status == RouteCandidate.Status.TREE_BRANCH_ACCEPTED) {
                    acceptedExplanations.add(reason);
                }
                if (targetId.equals(third.getFeature().getId()) && attemptId.contains(":lookahead:")) {
                    thirdLookaheadGoals.add(destination);
                }
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress,
                        Arrays.asList(seed, first, second, third), area, 300);

        RouteTreeOptimizer.TreeCandidate grown = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:group:"))
                .filter(candidate -> candidate.getVariant().getNodes().stream()
                        .filter(node -> "oks_connection_point".equals(node.getType())).count() == 4)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "lookahead must validate the complete A+B+C distribution tree"));
        assertTrue(grown.getValidation().isAcceptable());
        assertTrue(grown.getVariant().getSegments().stream().anyMatch(segment ->
                segment.getFlowTph().compareTo(BigDecimal.valueOf(6)) >= 0));
        assertTrue(result.getCandidates().stream().anyMatch(candidate ->
                !candidate.getVariant().getId().startsWith("navigator:group:")
                        && candidate.getVariant().getNodes().stream()
                                .filter(node -> "oks_connection_point".equals(node.getType())).count() == 4),
                "the independently grown alternative must remain available");
        assertEquals(grown.getTotalLengthM(), new VariantCostCalculator().calculate(grown.getVariant(),
                grown.getValidation(), inputs, Collections.emptyList()).getSummary()
                .getNewNetworkLengthM().doubleValue(), 1e-9,
                "the shared physical network is priced by unique stored segments");
        assertEquals(3, acceptedExplanations.size());
        assertTrue(!thirdLookaheadGoals.isEmpty() && thirdLookaheadGoals.get(0).getY() > 0.25,
                "the third target should first try the newly built distribution branch");
        assertTrue(acceptedExplanations.stream().allMatch(reason ->
                reason.contains("прирост стоимости") && reason.contains("уникальной длины")));
    }

    @Test
    void navigatorPreservesADifferentValidatedPairWithTheSameTargetsForPortfolioGrowth() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 1.0, point(-10, 0));
        FeatureIndex.IndexedFeature a = target(TypedId.of("A"), 0.5, point(10, 40));
        FeatureIndex.IndexedFeature b = target(TypedId.of("B"), 0.5, point(26, 27));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, a, b,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (50 0, 60 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(50, 0)));
        Envelope area = new Envelope(-15, 65, -5, 45);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(50, 0)), area)));
        List<Variant> groupTrees = new ArrayList<>();
        Map<String, Variant> portfolioStarts = new LinkedHashMap<>();

        new MultiTargetRouter(SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                requests, inputs, 3, null, 1, new RouteProgressListener() {
                    @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
                    @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                        if ("group:seed0".equals(trajectoryId)) groupTrees.add(checkedTree);
                        if (trajectoryId.startsWith("portfolio:")) {
                            portfolioStarts.putIfAbsent(trajectoryId, checkedTree);
                        }
                    }
                }, Arrays.asList(seed, a, b), area, 300);

        Variant primary = groupTrees.stream().filter(tree -> tree.getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count() == 3)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the primary A+B pair must be a complete validated tree"));
        Variant alternative = portfolioStarts.values().stream()
                .filter(tree -> tree.getNodes().stream()
                        .filter(node -> "oks_connection_point".equals(node.getType())).count() == 3)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "a distinct A+B corridor must remain available for later branches"));
        Geometry primaryShape = FACTORY.buildGeometry(primary.getSegments().stream()
                .map(Variant.Segment::getGeometry).collect(Collectors.toList()));
        Geometry alternativeShape = FACTORY.buildGeometry(alternative.getSegments().stream()
                .map(Variant.Segment::getGeometry).collect(Collectors.toList()));
        assertTrue(DiscreteHausdorffDistance.distance(primaryShape, alternativeShape) >= 4.0);
    }

    @Test
    void navigatorKeepsAGroupAnchorThatOnlyPaysOffAfterLaterBranches() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 1.0, point(-10, 0));
        FeatureIndex.IndexedFeature a = target(TypedId.of("A"), 0.5, point(10, 40));
        FeatureIndex.IndexedFeature b = target(TypedId.of("B"), 0.5, point(26, 27));
        FeatureIndex.IndexedFeature c = target(TypedId.of("C"), 0.5, point(35, 15));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, a, b, c,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (50 0, 60 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(50, 0)));
        Envelope area = new Envelope(-15, 65, -5, 45);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(50, 0)), area)));
        List<Point> successfulLongAnchors = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeAttemptCompleted(TypedId targetId, String attemptId,
                    Point destination, RouteCandidate.Status status, String reason, Double lengthM) {
                if (targetId.equals(a.getFeature().getId()) && attemptId.contains(":anchor")
                        && status == RouteCandidate.Status.FOUND && destination != null
                        && destination.distance(point(40, 0)) < 0.01) {
                    successfulLongAnchors.add(destination);
                }
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress, Arrays.asList(seed, a, b, c), area, 300);

        RouteTreeOptimizer.TreeCandidate group = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:group:"))
                .filter(candidate -> candidate.getVariant().getNodes().stream()
                        .filter(node -> "oks_connection_point".equals(node.getType())).count() == 4)
                .findFirst().orElseThrow(() -> new AssertionError("A, B and C must join one validated tree"));
        assertTrue(group.getValidation().isAcceptable());
        assertTrue(!successfulLongAnchors.isEmpty(), "the rootward A anchor must be searched and validated");
        assertTrue(group.getVariant().getNodes().stream().anyMatch(node ->
                "heat_chamber".equals(node.getType())
                        && Math.hypot(node.getX() - 40, node.getY()) < 0.01),
                "the selected distribution tree must retain the rootward A anchor");
        assertTrue(group.getTotalLengthM() < 131.0,
                "locally shortest first connection costs 131 m after B and C; group="
                        + group.getTotalLengthM());
    }

    @Test
    void navigatorGrowsTwoSeparatedGroupsWithoutLosingTheFirstPair() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 2.0, point(0, 0));
        FeatureIndex.IndexedFeature northA = target(TypedId.of("north-a"), 2.0, point(5, 8));
        FeatureIndex.IndexedFeature northB = target(TypedId.of("north-b"), 2.0, point(6, 10));
        FeatureIndex.IndexedFeature southA = target(TypedId.of("south-a"), 2.0, point(5, -8));
        FeatureIndex.IndexedFeature southB = target(TypedId.of("south-b"), 2.0, point(6, -10));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, northA, northB, southA, southB,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)));
        Envelope area = new Envelope(-5, 35, -15, 15);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(10, 0)), area)));
        List<Variant> groupSnapshots = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                if (trajectoryId.startsWith("group:")) groupSnapshots.add(checkedTree);
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress,
                        Arrays.asList(seed, northA, northB, southA, southB), area, 300);

        RouteTreeOptimizer.TreeCandidate grouped = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:group:"))
                .findFirst().orElseThrow(() -> new AssertionError("group trajectory missing"));
        assertTrue(grouped.getValidation().isAcceptable());
        assertEquals(5, grouped.getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(3, groupSnapshots.size(), "the live tree grows after each complete pair");
    }

    @Test
    void focusedGroupStartsFromAnAlreadyValidatedTwoRootForest() throws Exception {
        Fixture fixture = fixture(false);
        List<FeatureIndex.IndexedFeature> inputs = fixture.inputs;
        List<FeatureIndex.IndexedFeature> targets = inputs.stream()
                .filter(input -> "oks_connection_point".equals(input.getFeature().getObjectType()))
                .collect(java.util.stream.Collectors.toList());
        Envelope area = new Envelope(-100, 100, -100, 100);
        List<List<RouteRequest>> requests = Arrays.asList(
                Collections.singletonList(request("first-route", targets.get(0), inputs,
                        attachment(TypedId.of("network-a"), TypedId.of("chamber-a"), point(15, 0)), area)),
                Collections.singletonList(request("second-route", targets.get(1), inputs,
                        attachment(TypedId.of("network-b"), TypedId.of("chamber-b"), point(15, 20)), area)));
        Map<String, Variant> firstGroupSnapshots = new LinkedHashMap<>();
        List<String> trajectoryIds = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                trajectoryIds.add(trajectoryId);
                if (trajectoryId.matches("group:seed\\d+")) {
                    firstGroupSnapshots.putIfAbsent(trajectoryId, checkedTree);
                }
            }
        };
        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR, true).route(
                        requests, inputs, 8, null, 1, progress, targets, area, 300);
        assertTrue(result.getStatus() != MultiTargetRouteResult.Status.INVALID_REQUEST);
        assertTrue(firstGroupSnapshots.values().stream().anyMatch(tree ->
                tree.getNodes().stream().filter(node -> "oks_connection_point".equals(node.getType())).count() == 2
                        && tree.getNodes().stream().filter(node -> "heat_chamber".equals(node.getType())
                                && node.getInputFeatureId() != null).count() == 2),
                "a fully checked two-root seed must enter GROUP_LOOKAHEAD");
        assertTrue(trajectoryIds.stream().anyMatch(id -> id.startsWith("group:continued")),
                "a validated group seed still gets a continuation slot when first growth adds no target");
        assertTrue(trajectoryIds.stream().noneMatch(id -> id.startsWith("shortest_valid:")),
                "focused mode spends no separate shortest-valid wave");
    }

    @Test
    void navigatorConnectsAnOddTailAfterGroupedGrowth() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 1.0, point(0, 0));
        FeatureIndex.IndexedFeature northA = target(TypedId.of("north-a"), 1.0, point(5, 8));
        FeatureIndex.IndexedFeature northB = target(TypedId.of("north-b"), 1.0, point(6, 10));
        FeatureIndex.IndexedFeature middleA = target(TypedId.of("middle-a"), 1.0, point(20, 8));
        FeatureIndex.IndexedFeature middleB = target(TypedId.of("middle-b"), 1.0, point(21, 10));
        FeatureIndex.IndexedFeature southA = target(TypedId.of("south-a"), 1.0, point(35, -8));
        FeatureIndex.IndexedFeature southB = target(TypedId.of("south-b"), 1.0, point(36, -10));
        List<FeatureIndex.IndexedFeature> targets = Arrays.asList(seed, northA, northB,
                middleA, middleB, southA, southB);
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(targets);
        inputs.add(indexed(properties(networkId, "heat_network", "diameter", 500),
                geometry("LINESTRING (50 0, 60 0)")));
        inputs.add(indexed(new FeatureProperties(chamberId, "heat_chamber"), point(50, 0)));
        Envelope area = new Envelope(-5, 65, -15, 15);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(50, 0)), area)));
        List<Variant> groupSnapshots = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                if (trajectoryId.startsWith("group:")) groupSnapshots.add(checkedTree);
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress, targets, area, 300);
        RouteTreeOptimizer.TreeCandidate grouped = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:group:"))
                .findFirst().orElseThrow(() -> new AssertionError("group trajectory missing"));
        assertTrue(grouped.getValidation().isAcceptable());
        assertEquals(7, grouped.getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(4, groupSnapshots.size(), "the seed and three complete growth steps are published");
    }

    @Test
    void navigatorStartsFromTheFarSharedCorridorBeforeAllIndependentSearches() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature far = target(TypedId.of("far"), 1.0, point(0, 0));
        FeatureIndex.IndexedFeature nearA = target(TypedId.of("near-a"), 1.0, point(5, 8));
        FeatureIndex.IndexedFeature nearB = target(TypedId.of("near-b"), 1.0, point(6, 10));
        FeatureIndex.IndexedFeature nearC = target(TypedId.of("near-c"), 1.0, point(12, -8));
        FeatureIndex.IndexedFeature nearD = target(TypedId.of("near-d"), 1.0, point(13, -10));
        List<FeatureIndex.IndexedFeature> targets = Arrays.asList(far, nearA, nearB, nearC, nearD);
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(targets);
        inputs.add(indexed(properties(networkId, "heat_network", "diameter", 500),
                geometry("LINESTRING (60 0, 70 0)")));
        inputs.add(indexed(new FeatureProperties(chamberId, "heat_chamber"), point(60, 0)));
        Envelope area = new Envelope(-5, 75, -15, 15);
        List<List<RouteRequest>> requests = new ArrayList<>();
        for (FeatureIndex.IndexedFeature target : targets) {
            requests.add(Collections.singletonList(request("independent-"
                    + target.getFeature().getId(), target, inputs,
                    attachment(networkId, chamberId, point(60, 0)), area)));
        }
        List<TypedId> initialSearches = new ArrayList<>();
        List<RouteRequest> seedRequests = new ArrayList<>();
        List<Variant> groupSnapshots = new ArrayList<>();
        Set<TypedId> districtAttemptTargets = new HashSet<>();
        int[] searchesBeforeTree = {-1};
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) {
                initialSearches.add(request.getTarget().getFeature().getId());
                seedRequests.add(request);
            }
            @Override public void onTreeGrowthStarted() {
                searchesBeforeTree[0] = new HashSet<>(initialSearches).size();
            }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                if ("group:seed0".equals(trajectoryId)) groupSnapshots.add(checkedTree);
            }
            @Override public void onTreeAttemptCompleted(TypedId targetId, String attemptId, Point destination,
                    RouteCandidate.Status status, String reason, Double lengthM) {
                if (attemptId.startsWith("navigator:group:seed0:district:")) {
                    districtAttemptTargets.add(targetId);
                }
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 32, null, 1, progress, targets, area, 300);

        assertEquals(far.getFeature().getId(), initialSearches.get(0),
                "the far line serving nearby targets should be checked as a trunk seed");
        assertTrue(seedRequests.subList(0, 3).stream().allMatch(request ->
                new BigDecimal("5").equals(request.getPlannedSectionFlowTph().stripTrailingZeros())),
                "ordinary section seeds must retain the full planned flow through forSingleTarget");
        assertTrue(seedRequests.stream().anyMatch(request -> request.getPlannedSectionFlowTph() == null),
                "unchanged independent seeds must remain live alternatives");
        assertTrue(seedRequests.size() <= 12, "reserved requests share the existing seed budget");
        assertEquals(Arrays.asList(far.getFeature().getId(), nearD.getFeature().getId(),
                        nearB.getFeature().getId()), initialSearches.subList(0, 3),
                "the first three seed searches should span the far, south and north corridors");
        assertTrue(searchesBeforeTree[0] > 0 && searchesBeforeTree[0] < requests.size(),
                "tree growth must start before all independent requests are searched");
        assertEquals(Collections.singleton(far.getFeature().getId()), groupSnapshots.get(0).getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType()))
                .map(Variant.Node::getInputFeatureId).collect(java.util.stream.Collectors.toSet()),
                "the group begins with the long far-target pipe, not a forest of independent routes");
        assertTrue(result.getTargetAttempts().values().stream().anyMatch(statuses ->
                statuses.contains(RouteCandidate.Status.SEED_SEARCH_SKIPPED)
                        && statuses.contains(RouteCandidate.Status.TREE_BRANCH_ACCEPTED)),
                "a target without an independent search must still be connected to new pipes");
        assertEquals(new HashSet<>(Arrays.asList(nearA.getFeature().getId(), nearB.getFeature().getId(),
                        nearC.getFeature().getId(), nearD.getFeature().getId())), districtAttemptTargets,
                "the checked district trunk must try every house in its selected section before global growth");
        assertTrue(result.getCandidates().stream().anyMatch(candidate ->
                candidate.getVariant().getId().startsWith("navigator:group:")
                        && candidate.getValidation().isAcceptable()
                        && candidate.getVariant().getNodes().stream()
                                .filter(node -> "oks_connection_point".equals(node.getType())).count() == 5),
                "a section-first trial must retain a validated shared network serving the whole section");
    }

    @Test
    void navigatorUsesRemainingSeedBudgetForAnAlternativeFirstTrunk() throws Exception {
        TypedId networkId = TypedId.of("network");
        FeatureIndex.IndexedFeature target = target(TypedId.of("first"), 1.0, point(0, 0));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        inputs.add(target);
        inputs.add(indexed(properties(networkId, "heat_network", "diameter", 500),
                geometry("LINESTRING (40 0, 60 0)")));
        inputs.add(indexed(new FeatureProperties(TypedId.of("near-chamber"), "heat_chamber"), point(40, 0)));
        inputs.add(indexed(new FeatureProperties(TypedId.of("far-chamber"), "heat_chamber"), point(60, 0)));
        Envelope area = new Envelope(-5, 65, -5, 5);
        List<List<RouteRequest>> requests = Collections.singletonList(Arrays.asList(
                request("near-seed", target, inputs,
                        attachment(networkId, TypedId.of("near-chamber"), point(40, 0)), area),
                request("far-seed", target, inputs,
                        attachment(networkId, TypedId.of("far-chamber"), point(60, 0)), area)));
        List<String> searched = new ArrayList<>();
        Map<String, Variant> groupStarts = new LinkedHashMap<>();
        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 8, null, 1, new RouteProgressListener() {
                            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) {
                                searched.add(request.getVariantId());
                            }
                            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                                if (trajectoryId.startsWith("group:")) groupStarts.putIfAbsent(trajectoryId, checkedTree);
                            }
                        }, Collections.singletonList(target), area, 300);

        assertEquals(Arrays.asList("near-seed", "far-seed"), searched);
        assertEquals(2, result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("exhaustive:"))
                .count(), "both verified first-trunk geometries must remain available for comparison");
        assertEquals(2, groupStarts.size(), "distinct first pipes to one building must both get group lookahead");
        assertTrue(groupStarts.values().stream().anyMatch(tree -> tree.getSegments().stream()
                .anyMatch(segment -> segment.getGeometry().getEnvelopeInternal().getMaxX() >= 59.0)));
    }

    @Test
    void navigatorCanPreferALongerFirstTrunkNearTheNextBuilding() throws Exception {
        TypedId eastNetwork = TypedId.of("east-network"), northNetwork = TypedId.of("north-network");
        FeatureIndex.IndexedFeature first = target(TypedId.of("first"), 1.0, point(0, 0));
        FeatureIndex.IndexedFeature next = target(TypedId.of("next"), 1.0, point(5, 40));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(Arrays.asList(first, next));
        inputs.add(indexed(properties(eastNetwork, "heat_network", "diameter", 500),
                geometry("LINESTRING (50 0, 60 0)")));
        inputs.add(indexed(properties(northNetwork, "heat_network", "diameter", 500),
                geometry("LINESTRING (0 70, 0 80)")));
        inputs.add(indexed(new FeatureProperties(TypedId.of("east-chamber"), "heat_chamber"), point(50, 0)));
        inputs.add(indexed(new FeatureProperties(TypedId.of("north-chamber"), "heat_chamber"), point(0, 70)));
        Envelope area = new Envelope(-10, 85, -10, 85);
        List<List<RouteRequest>> requests = Collections.singletonList(Arrays.asList(
                request("cheap-east", first, inputs,
                        attachment(eastNetwork, TypedId.of("east-chamber"), point(50, 0)), area),
                request("future-north", first, inputs,
                        attachment(northNetwork, TypedId.of("north-chamber"), point(0, 70)), area)));
        List<Variant> groupSnapshots = new ArrayList<>();
        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 8, null, 1, new RouteProgressListener() {
                            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
                            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                                if ("group:seed0".equals(trajectoryId)) groupSnapshots.add(checkedTree);
                            }
                        }, Arrays.asList(first, next), area, 300);

        assertTrue(result.getCandidates().stream().anyMatch(candidate ->
                candidate.getVariant().getSegments().stream().anyMatch(segment ->
                        segment.getGeometry().getLength() >= 49.0
                                && segment.getGeometry().getEnvelopeInternal().getMaxX() >= 49.0)),
                "the cheaper east-facing trunk remains a candidate");
        assertTrue(!groupSnapshots.isEmpty());
        assertTrue(groupSnapshots.get(0).getSegments().stream().anyMatch(segment ->
                        segment.getGeometry().getLength() >= 69.0
                                && segment.getGeometry().getEnvelopeInternal().getMaxY() >= 69.0),
                "group growth should start from the validated north-facing trunk near the next target");
    }

    @Test
    void navigatorGrowsIndependentTreesOnDifferentWorkersWithTheSameFinalResults() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        List<FeatureIndex.IndexedFeature> targets = Arrays.asList(
                target(TypedId.of("far"), 1.0, point(0, 0)),
                target(TypedId.of("near-a"), 1.0, point(5, 8)),
                target(TypedId.of("near-b"), 1.0, point(6, 10)),
                target(TypedId.of("near-c"), 1.0, point(12, -8)),
                target(TypedId.of("near-d"), 1.0, point(13, -10)));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(targets);
        inputs.add(indexed(properties(networkId, "heat_network", "diameter", 500),
                geometry("LINESTRING (60 0, 70 0)")));
        inputs.add(indexed(new FeatureProperties(chamberId, "heat_chamber"), point(60, 0)));
        Envelope area = new Envelope(-5, 75, -15, 15);
        List<List<RouteRequest>> requests = new ArrayList<>();
        for (FeatureIndex.IndexedFeature target : targets) {
            requests.add(Collections.singletonList(request("independent-"
                    + target.getFeature().getId(), target, inputs,
                    attachment(networkId, chamberId, point(60, 0)), area)));
        }
        List<String> originalGeometry = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : inputs) originalGeometry.add(input.getGeometry().toText());
        MultiTargetRouter navigator = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR);
        MultiTargetRouteResult sequential = navigator.route(requests, inputs, 32,
                null, 1, RouteProgressListener.NONE, targets, area, 300);
        Map<String, String> groupThreads = new ConcurrentHashMap<>();
        Map<String, Long> portfolioInitialTargets = new ConcurrentHashMap<>();
        Set<TypedId> observedTargets = ConcurrentHashMap.newKeySet();
        List<TypedId> groupAttemptOrder = Collections.synchronizedList(new ArrayList<>());
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                if (trajectoryId.startsWith("group:")) {
                    groupThreads.putIfAbsent(trajectoryId, Thread.currentThread().getName());
                }
                if (trajectoryId.startsWith("portfolio:")) {
                    portfolioInitialTargets.putIfAbsent(trajectoryId, checkedTree.getNodes().stream()
                            .filter(node -> "oks_connection_point".equals(node.getType())).count());
                }
            }
            @Override public void onTreeAttemptCompleted(TypedId targetId, String attemptId, Point destination,
                    RouteCandidate.Status status, String reason, Double lengthM) {
                observedTargets.add(targetId);
                if (attemptId.startsWith("navigator:group:seed0:")
                        && !groupAttemptOrder.contains(targetId)) groupAttemptOrder.add(targetId);
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(4);
        MultiTargetRouteResult parallel;
        try {
            parallel = navigator.route(requests, inputs, 32, executor, 4, progress,
                    targets, area, 300);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(sequential.getStatus(), parallel.getStatus());
        assertEquals(sequential.getTargetAttempts(), parallel.getTargetAttempts());
        assertEquals(sequential.getUnsearchedTargetIds(), parallel.getUnsearchedTargetIds());
        assertEquals(sequential.getUnroutedTargetIds(), parallel.getUnroutedTargetIds());
        assertEquals(candidateSignatures(sequential), candidateSignatures(parallel));
        ExecutorService eightWorkers = Executors.newFixedThreadPool(8);
        MultiTargetRouteResult eightParallel;
        try {
            eightParallel = navigator.route(requests, inputs, 32, eightWorkers, 8,
                    RouteProgressListener.NONE, targets, area, 300);
        } finally {
            eightWorkers.shutdownNow();
        }
        assertEquals(candidateSignatures(sequential), candidateSignatures(eightParallel));
        assertEquals(sequential.getTargetAttempts(), eightParallel.getTargetAttempts());
        assertEquals(sequential.getUnsearchedTargetIds(), eightParallel.getUnsearchedTargetIds());
        for (RouteTreeOptimizer.TreeCandidate candidate : parallel.getCandidates()) {
            assertTrue(candidate.getValidation().isAcceptable());
            String candidateId = candidate.getVariant().getId();
            assertEquals(sequential.getUnsearchedReasonsForCandidate(candidateId),
                    parallel.getUnsearchedReasonsForCandidate(candidateId));
        }
        assertTrue(groupThreads.size() >= 2, "at least two checked group seeds should be searched");
        assertTrue(new HashSet<>(groupThreads.values()).size() >= 2,
                "group tree growth must use more than one worker thread");
        assertTrue(observedTargets.size() >= 2, "different buildings should receive live attempts");
        assertTrue(groupAttemptOrder.indexOf(TypedId.of("near-d")) >= 0
                        && groupAttemptOrder.indexOf(TypedId.of("near-d")) <= 2,
                "a farther corridor must receive an early group attempt: " + groupAttemptOrder);
        assertTrue(portfolioInitialTargets.values().stream().anyMatch(count -> count >= 3)
                        || parallel.getCandidates().stream().anyMatch(candidate ->
                                candidate.getVariant().getId().startsWith("navigator:group:")
                                        && candidate.getVariant().getNodes().stream()
                                                .filter(node -> "oks_connection_point".equals(node.getType()))
                                                .count() == targets.size()),
                "a validated alternative pair must continue unless the section trial already serves all targets");
        List<String> geometryAfter = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : inputs) geometryAfter.add(input.getGeometry().toText());
        assertEquals(originalGeometry, geometryAfter);
    }

    @Test
    void navigatorWidensSeedSearchWhenTheFirstFarCorridorIsInvalid() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature far = target(TypedId.of("far"), 1.0, point(0, 0));
        FeatureIndex.IndexedFeature near = target(TypedId.of("near"), 1.0, point(20, 5));
        FeatureIndex.IndexedFeature neighbor = target(TypedId.of("neighbor"), 1.0, point(21, 7));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(far, near, neighbor,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (50 0, 60 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(50, 0)));
        Envelope area = new Envelope(-5, 65, -10, 12);
        List<List<RouteRequest>> requests = Arrays.asList(
                Collections.singletonList(request("far-invalid", far, inputs,
                        attachment(networkId, chamberId, point(50, 0)),
                        new Envelope(10, 65, -10, 12))),
                Collections.singletonList(request("near-valid", near, inputs,
                        attachment(networkId, chamberId, point(50, 0)), area)),
                Collections.singletonList(request("neighbor-valid", neighbor, inputs,
                        attachment(networkId, chamberId, point(50, 0)), area)));
        List<TypedId> searched = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) {
                searched.add(request.getTarget().getFeature().getId());
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress,
                        Arrays.asList(far, near, neighbor), area, 300);

        assertEquals(far.getFeature().getId(), searched.get(0));
        assertTrue(searched.contains(near.getFeature().getId()));
        assertTrue(result.getCandidates().stream().anyMatch(candidate ->
                candidate.getValidation().isAcceptable()
                        && candidate.getVariant().getNodes().stream().anyMatch(node ->
                                near.getFeature().getId().equals(node.getInputFeatureId()))));
    }

    @Test
    void navigatorKeepsAValidatedNearbyAnchorWhenItsPairIsBlocked() throws Exception {
        TypedId nearId = TypedId.of("z-near"), farId = TypedId.of("a-far");
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 2.0, point(0, 0));
        FeatureIndex.IndexedFeature near = target(nearId, 2.0, point(5, 7));
        FeatureIndex.IndexedFeature far = target(farId, 2.0, point(5, 11.5));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, near, far,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)),
                indexed(properties(TypedId.of("wall"), "restriction", "restriction_type", "park"),
                        geometry("POLYGON ((-5 9, 35 9, 35 9.4, -5 9.4, -5 9))")));
        Envelope area = new Envelope(-5, 35, -12, 15);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(10, 0)), area)));
        List<String> events = new ArrayList<>();
        List<TypedId> anchorOrder = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                if ("group:seed0".equals(trajectoryId) && checkedTree.getNodes().stream()
                        .anyMatch(node -> nearId.equals(node.getInputFeatureId()))) {
                    events.add("accepted-near");
                }
            }
            @Override public void onTreeAttemptCompleted(TypedId targetId, String attemptId, Point destination,
                    RouteCandidate.Status status, String reason, Double lengthM) {
                if (!attemptId.startsWith("navigator:group:seed0:")) return;
                if (attemptId.endsWith(":anchor")) anchorOrder.add(targetId);
                if (attemptId.contains(":direct:") || attemptId.contains(":search:")) {
                    events.add("fallback");
                }
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress,
                        Arrays.asList(seed, near, far), area, 300);

        assertEquals(nearId, anchorOrder.get(0), "the legal nearer branch must precede the ID-first far branch");
        int acceptedNear = events.indexOf("accepted-near");
        assertTrue(acceptedNear >= 0, "a validated first branch must survive the failed pair");
        int firstFallback = events.indexOf("fallback");
        assertTrue(firstFallback < 0 || acceptedNear < firstFallback,
                "the validated nearby branch must be published before any repeated fallback search");
        RouteTreeOptimizer.TreeCandidate grouped = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:group:"))
                .findFirst().orElseThrow(() -> new AssertionError("validated single branch is missing"));
        assertTrue(grouped.getValidation().isAcceptable());
        assertTrue(grouped.getVariant().getNodes().stream()
                .anyMatch(node -> nearId.equals(node.getInputFeatureId())));
        assertTrue(grouped.getVariant().getNodes().stream()
                .noneMatch(node -> farId.equals(node.getInputFeatureId())));
        assertTrue(anchorOrder.contains(farId), "the distant target must still receive a real first visit");
    }

    @Test
    void navigatorTriesASecondPartnerWhenNearestPairsAreSeparatedByABarrier() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        TypedId lowerAId = TypedId.of("a-lower"), lowerBId = TypedId.of("c-lower");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 2.0, point(0, 0));
        FeatureIndex.IndexedFeature lowerA = target(lowerAId, 2.0, point(5, 7));
        FeatureIndex.IndexedFeature upperA = target(TypedId.of("b-upper"), 2.0, point(5, 11.5));
        FeatureIndex.IndexedFeature lowerB = target(lowerBId, 2.0, point(15, 7));
        FeatureIndex.IndexedFeature upperB = target(TypedId.of("d-upper"), 2.0, point(15, 11.5));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, lowerA, upperA, lowerB, upperB,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        geometry("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)),
                indexed(properties(TypedId.of("wall"), "restriction", "restriction_type", "park"),
                        geometry("POLYGON ((-5 9, 35 9, 35 9.4, -5 9.4, -5 9))")));
        Envelope area = new Envelope(-5, 35, -12, 15);
        List<List<RouteRequest>> requests = Collections.singletonList(Collections.singletonList(
                request("seed-route", seed, inputs, attachment(networkId, chamberId, point(10, 0)), area)));
        List<String> anchorSearchKeys = new ArrayList<>();
        int[] treeEpoch = {0};
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) {
                if ("group:seed0".equals(trajectoryId)) treeEpoch[0]++;
            }
            @Override public void onTreeAttemptCompleted(TypedId targetId, String attemptId, Point destination,
                    RouteCandidate.Status status, String reason, Double lengthM) {
                if (attemptId.startsWith("navigator:group:seed0:") && attemptId.endsWith(":anchor")) {
                    anchorSearchKeys.add(treeEpoch[0] + ":" + targetId + ":"
                            + destination.getX() + ":" + destination.getY());
                }
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(
                        requests, inputs, 3, null, 1, progress,
                        Arrays.asList(seed, lowerA, upperA, lowerB, upperB), area, 300);

        RouteTreeOptimizer.TreeCandidate grouped = result.getCandidates().stream()
                .filter(candidate -> candidate.getVariant().getId().startsWith("navigator:group:"))
                .filter(candidate -> candidate.getVariant().getNodes().stream()
                        .map(Variant.Node::getInputFeatureId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet())
                        .containsAll(Arrays.asList(lowerAId, lowerBId)))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "the reachable lower pair must survive blocked nearest pairs"));
        assertTrue(grouped.getValidation().isAcceptable());
        assertEquals(anchorSearchKeys.size(), new HashSet<>(anchorSearchKeys).size(),
                "the same goal must not launch a second anchor search on an unchanged tree");
    }

    @Test
    void treeGoalWavesIncludeTheFifthTrunkAndFreeChambers() {
        List<Variant.Node> nodes = new ArrayList<>();
        List<Variant.Segment> segments = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            String from = "from-" + index, to = "to-" + index;
            double x = index * 10.0;
            nodes.add(new Variant.Node(from, x, 0, "technical_node"));
            nodes.add(new Variant.Node(to, x, 10, "technical_node"));
            LineString geometry = FACTORY.createLineString(new Coordinate[] {
                    new Coordinate(x, 0), new Coordinate(x, 10)});
            segments.add(new Variant.Segment("trunk-" + index, from, to, geometry,
                    from, to, BigDecimal.ONE, 50, "base", Collections.emptySet(), BigDecimal.ONE));
        }
        nodes.add(new Variant.Node("free-chamber", 60, 5, "heat_chamber"));
        nodes.add(new Variant.Node("chamber-tail", 70, 5, "technical_node"));
        segments.add(new Variant.Segment("chamber-leg", "free-chamber", "chamber-tail",
                FACTORY.createLineString(new Coordinate[] {new Coordinate(60, 5), new Coordinate(70, 5)}),
                "chamber-tail", "free-chamber", BigDecimal.ONE, 50, "base",
                Collections.emptySet(), BigDecimal.ONE));
        Variant tree = new Variant("goal-enumeration", nodes, segments);
        FeatureIndex.IndexedFeature target = target(TypedId.of("new-target"), 2.0, point(0, 5));

        List<MultiTargetRouter.TreeGoal> goals = MultiTargetRouter.treeGoals(tree, target);

        assertTrue(goals.stream().anyMatch(goal -> "trunk-4".equals(goal.getTrunkId())),
                "a fifth trunk must not disappear behind the old four-trunk cutoff");
        assertTrue(goals.stream().anyMatch(goal -> "free-chamber".equals(goal.getChamberNodeId())));
    }

    @Test
    void treeGoalsKeepSourcewardQuarterMetreAndExactProjectionSeparate() {
        Variant tree = new Variant("right-angle-goals", Arrays.asList(
                new Variant.Node("far", 0, 0, "technical_node"),
                new Variant.Node("source", 0, 10, "technical_node")),
                Collections.singletonList(new Variant.Segment("trunk", "far", "source",
                        FACTORY.createLineString(new Coordinate[] {
                                new Coordinate(0, 0), new Coordinate(0, 10)}),
                        "far", "source", BigDecimal.ONE, 50, "base",
                        Collections.emptySet(), BigDecimal.ONE)));
        FeatureIndex.IndexedFeature target = target(TypedId.of("near-target"), 2.0, point(4, 5));

        List<MultiTargetRouter.TreeGoal> goals = MultiTargetRouter.treeGoals(tree, target);

        assertEquals("trunk", goals.get(0).getTrunkId());
        assertEquals(0.0, goals.get(0).getPoint().getX(), 1e-9);
        assertEquals(5.25, goals.get(0).getPoint().getY(), 1e-9,
                "the small sourceward offset should be tested before the numerically exact right angle");
        assertEquals("trunk", goals.get(1).getTrunkId());
        assertEquals(5.0, goals.get(1).getPoint().getY(), 1e-9,
                "the exact projection remains an independent fallback goal");
    }

    @Test
    void groupLookaheadReservesOneAttemptForASeparateDistrict() {
        Variant.Segment base = new Variant.Segment("base", "seed", "source",
                FACTORY.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(10, 0)}),
                "seed", "source", BigDecimal.ONE, 50, "base", Collections.emptySet(), BigDecimal.ONE);
        Variant parent = new Variant("parent", Arrays.asList(
                new Variant.Node("seed", 0, 0, "technical_node"),
                new Variant.Node("source", 10, 0, "heat_chamber")), Collections.singletonList(base));
        Variant pair = new Variant("pair", Arrays.asList(
                new Variant.Node("seed", 0, 0, "technical_node"),
                new Variant.Node("source", 10, 0, "heat_chamber"),
                new Variant.Node("north", 0, 20, "technical_node")), Arrays.asList(base,
                new Variant.Segment("north-branch", "seed", "north",
                        FACTORY.createLineString(new Coordinate[] {new Coordinate(0, 0), new Coordinate(0, 20)}),
                        "north", "seed", BigDecimal.ONE, 50, "base", Collections.emptySet(), BigDecimal.ONE)));
        FeatureIndex.IndexedFeature nearby = target(TypedId.of("nearby"), 1.0, point(0, 21));
        FeatureIndex.IndexedFeature secondNearby = target(TypedId.of("second-nearby"), 1.0, point(1, 22));
        FeatureIndex.IndexedFeature south = target(TypedId.of("south"), 1.0, point(5, -40));

        List<FeatureIndex.IndexedFeature> selected = MultiTargetRouter.selectLookaheadTargets(parent, pair,
                Arrays.asList(nearby, secondNearby, south), new Envelope(-10, 20, -50, 30));

        assertEquals(Arrays.asList(nearby, south), selected,
                "the second lookahead slot must sample another district even without Euclidean distance gain");
    }

    @Test
    void aSectionRemainsAvailableFromAnotherMemberWhenItsFarAnchorCannotSeed() {
        TypedId far = TypedId.of("far"), middle = TypedId.of("middle"), near = TypedId.of("near");
        List<DemandGroupPlanner.Demand> demands = Arrays.asList(
                new DemandGroupPlanner.Demand(far, point(0, 0), BigDecimal.ONE),
                new DemandGroupPlanner.Demand(middle, point(20, 8), BigDecimal.ONE),
                new DemandGroupPlanner.Demand(near, point(40, -8), BigDecimal.ONE));
        DemandGroupPlanner.PassageProbe probe = new DemandGroupPlanner.PassageProbe() {
            @Override public DemandGroupPlanner.Passage betweenExits(DemandGroupPlanner.Demand first,
                    DemandGroupPlanner.Demand second) {
                return DemandGroupPlanner.Passage.possible(first.getProjectedExit()
                        .distance(second.getProjectedExit()));
            }
            @Override public DemandGroupPlanner.Passage toRoot(DemandGroupPlanner.Demand target,
                    DemandGroupPlanner.RootOption root, BigDecimal flow) {
                return DemandGroupPlanner.Passage.possible(target.getProjectedExit()
                        .distance(root.getProjectedEndpoint()));
            }
        };
        DemandGroupPlanner.Plan plan = new DemandGroupPlanner().plan(demands,
                Collections.singletonList(new DemandGroupPlanner.RootOption(TypedId.of("root"),
                        TypedId.of("network"), point(80, 0))), probe,
                new DemandGroupPlanner.Limits(3, 1, 2, 3, 3, 3, 3, 1, new BigDecimal("10")));

        assertEquals(new HashSet<>(Arrays.asList(far, middle, near)),
                new HashSet<>(MultiTargetRouter.sectionMembersBySeed(plan).get(middle)),
                "a valid ordinary seed from another member must still inherit the whole section");
    }

    @Test
    void movesAJoinBeyondTheWholeRootwardClearanceBottleneckAfterDiameterGrowth() throws Exception {
        LineString trunk = FACTORY.createLineString(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(100, 0)});
        Variant tree = new Variant("narrow-parent", Arrays.asList(
                new Variant.Node("consumer", 0, 0, "heat_chamber"),
                new Variant.Node("source", 100, 0, "heat_chamber")),
                Collections.singletonList(new Variant.Segment("trunk", "consumer", "source",
                        trunk, "consumer", "source", new BigDecimal("62.11"), 150,
                        "base", Collections.emptySet(), BigDecimal.ONE)));
        FeatureIndex.IndexedFeature next = target(TypedId.of("southern"), 34.53, point(10, -20));
        FeatureIndex.IndexedFeature narrowOks = indexed(
                properties(TypedId.of("oks-83"), "restriction", "restriction_type", "oks"),
                geometry("POLYGON ((0 5.37235, 50 5.37235, 50 15, 0 15, 0 5.37235))"));
        ClearanceCheck clearance = new ClearanceCheck();
        double oldThreshold = clearance.requiredCenterlineDistanceM("oks", 150,
                RuleCatalog.diameter(150).get().getPairWidthM(), null);
        double newThreshold = clearance.requiredCenterlineDistanceM("oks", 200,
                RuleCatalog.diameter(200).get().getPairWidthM(), null);
        assertTrue(clearance.respectsClearance(trunk, narrowOks.getGeometry(), oldThreshold,
                narrowOks.getFeature().getId(), Collections.emptyList()));
        assertTrue(!clearance.respectsClearance(trunk, narrowOks.getGeometry(), newThreshold,
                narrowOks.getFeature().getId(), Collections.emptyList()));

        List<MultiTargetRouter.TreeGoal> goals = router.treeGoalsForSearch(tree, next,
                Arrays.asList(next, narrowOks), new Envelope(-10, 110, -30, 30));

        assertTrue(!goals.isEmpty(), "a new legal join must be generated beyond fixed 30 m offsets");
        assertTrue(goals.stream().allMatch(goal -> goal.getPoint().getX() > 40.0),
                "the first locally close joins would enlarge a rootward pipe too close to oks-83");
        assertTrue(goals.stream().anyMatch(goal -> goal.getPoint().getX() > 50.0),
                "the sourceward side of the bottleneck must remain searchable");
    }

    @Test
    void sectionReserveReachesGroupGrowthAndAvoidsADuplicateLongBranch() throws Exception {
        FeatureIndex.IndexedFeature far = target(TypedId.of("far"), 3.0, point(0, 0));
        FeatureIndex.IndexedFeature next = target(TypedId.of("next"), 100.0, point(20, -10));
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source");
        List<FeatureIndex.IndexedFeature> targets = Arrays.asList(far, next);
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>(targets);
        inputs.add(indexed(properties(networkId, "heat_network", "diameter", 500),
                geometry("LINESTRING (100 0, 110 0)")));
        inputs.add(indexed(new FeatureProperties(chamberId, "heat_chamber"), point(100, 0)));
        inputs.add(indexed(properties(TypedId.of("bottleneck"), "restriction", "restriction_type", "oks"),
                geometry("POLYGON ((30 5.4, 70 5.4, 70 15, 30 15, 30 5.4))")));
        Envelope area = new Envelope(-5, 115, -20, 25);
        List<List<RouteRequest>> requests = new ArrayList<>();
        for (FeatureIndex.IndexedFeature target : targets) requests.add(Collections.singletonList(
                request("single-" + target.getFeature().getId(), target, inputs,
                        attachment(networkId, chamberId, point(100, 0)), area)));
        List<Variant> firstScenario = new ArrayList<>();
        RouteProgressListener progress = new RouteProgressListener() {
            @Override public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) { }
            @Override public void onTreeTrajectoryUpdated(String id, Variant tree) {
                if ("group:seed0".equals(id) || "group:seed0:district".equals(id)) firstScenario.add(tree);
            }
        };

        MultiTargetRouteResult result = new MultiTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).route(requests, inputs,
                        32, null, 1, progress, targets, area, 300);

        assertTrue(!firstScenario.isEmpty());
        Variant seed = firstScenario.get(0);
        assertEquals(Collections.singleton(far.getFeature().getId()), seed.getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType()))
                .map(Variant.Node::getInputFeatureId).collect(java.util.stream.Collectors.toSet()));
        double threshold = new ClearanceCheck().requiredCenterlineDistanceM("oks", 200,
                RuleCatalog.diameter(200).get().getPairWidthM(), null);
        Geometry building = inputs.get(inputs.size() - 1).getGeometry();
        assertTrue(seed.getSegments().stream().allMatch(segment -> segment.getDiameterMm() == 50
                && segment.getFlowTph().compareTo(new BigDecimal("3")) == 0
                && segment.getGeometry().distance(building) >= threshold - 1e-8),
                "the first group must actually receive the far seed with reserved space and real demand");
        assertTrue(firstScenario.stream().anyMatch(tree -> tree.getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count() == 2
                && tree.getSegments().stream().mapToDouble(segment -> segment.getGeometry().getLength()).sum() < 130),
                "that same far-seed scenario must accept the short shared connection");

        assertTrue(result.getCandidates().stream().anyMatch(candidate ->
                candidate.getVariant().getId().startsWith("navigator:group:")
                        && candidate.getVariant().getNodes().stream()
                                .filter(node -> "oks_connection_point".equals(node.getType())).count() == 2
                        && candidate.getTotalLengthM() < 130.0 && candidate.getValidation().isAcceptable()),
                "section planning must carry the reserved corridor into a fully validated short shared tree");
    }

    @Test
    void classifiesVertexLimitAsUnsearchedInsteadOfUnrouted() throws Exception {
        Fixture fixture = fixture(true);
        RouteRequest blocked = fixture.requests.get(2).get(0);
        RouteRequest limited = new RouteRequest("blocked-limited", blocked.getTarget(), blocked.getAttachment(),
                blocked.getInputs(), blocked.getSearchArea(), 2);

        MultiTargetRouteResult result = router.route(
                Collections.singletonList(Collections.singletonList(limited)), fixture.inputs, 20);

        assertEquals(MultiTargetRouteResult.Status.SEARCH_LIMIT_REACHED, result.getStatus());
        assertTrue(result.getUnroutedTargetIds().isEmpty());
        assertEquals(Collections.singletonList(fixture.blockedTargetId), result.getUnsearchedTargetIds());
        assertTrue(result.getTargetAttempts().get(fixture.blockedTargetId)
                .contains(RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED));
    }

    @Test
    void boundedParallelSearchPreservesSequentialResultsAndInputGeometries() throws Exception {
        Fixture complete = fixture(false);
        assertParallelMatchesSequential(complete.requests, complete.inputs, 20);

        Fixture partial = fixture(true);
        assertParallelMatchesSequential(partial.requests, partial.inputs, 20);

        RouteRequest blocked = partial.requests.get(2).get(0);
        RouteRequest limited = new RouteRequest("blocked-limited", blocked.getTarget(), blocked.getAttachment(),
                blocked.getInputs(), blocked.getSearchArea(), 2);
        List<List<RouteRequest>> limitedRequests = new ArrayList<>(partial.requests);
        limitedRequests.set(2, Collections.singletonList(limited));
        assertParallelMatchesSequential(limitedRequests, partial.inputs, 20);
    }

    @Test
    void preservesResultsWhenSearchesSpanSeveralParallelBatches() throws Exception {
        Fixture fixture = fixture(false);
        List<List<RouteRequest>> requests = new ArrayList<>();
        for (List<RouteRequest> targetAlternatives : fixture.requests) {
            List<RouteRequest> expanded = new ArrayList<>();
            for (RouteRequest request : targetAlternatives) expanded.add(request);
            for (int i = 1; i <= 3; i++) {
                RouteRequest original = targetAlternatives.get(0);
                expanded.add(new RouteRequest(original.getVariantId() + "-repeat-" + i,
                        original.getTarget(), original.getAttachment(), original.getInputs(),
                        original.getSearchArea(), original.getMaximumVisibilityVertices()));
            }
            requests.add(expanded);
        }
        assertParallelMatchesSequential(requests, fixture.inputs, 20);
    }

    @Test
    void navigatorGrowthWaveActuallyOverlapsWorkAndKeepsSeedOrder() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<java.util.concurrent.Callable<String>> scenarios = Arrays.asList(
                () -> {
                    bothStarted.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("first seed timed out");
                    Thread.sleep(40);
                    return "seed0";
                },
                () -> {
                    bothStarted.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("second seed timed out");
                    return "seed1";
                });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        Future<List<String>> result = coordinator.submit(() ->
                MultiTargetRouter.executeNavigatorGrowthWave(scenarios, workers, 2));
        boolean overlapped;
        try {
            overlapped = bothStarted.await(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        try {
            assertTrue(overlapped, "both independent seeds must start before either one finishes");
            assertEquals(Arrays.asList("seed0", "seed1"), result.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            coordinator.shutdownNow();
        }
    }

    @Test
    void slidingWindowStartsNextBatchBeforeSlowSearchCompletes() throws Exception {
        CountDownLatch releaseSlowTask = new CountDownLatch(1);
        CountDownLatch nextBatchStarted = new CountDownLatch(1);
        List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> {
            if (!releaseSlowTask.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Slow task was not released");
            return 0;
        });
        for (int i = 1; i < 6; i++) {
            final int taskIndex = i;
            tasks.add(() -> {
                if (taskIndex == 4) nextBatchStarted.countDown();
                return taskIndex;
            });
        }
        ExecutorService searchExecutor = Executors.newFixedThreadPool(4);
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        Future<List<Integer>> results = coordinator.submit(() ->
                MultiTargetRouter.executeSlidingWindow(tasks, searchExecutor, 4, ignored -> { }));
        boolean pipelined;
        try {
            pipelined = nextBatchStarted.await(2, TimeUnit.SECONDS);
        } finally {
            releaseSlowTask.countDown();
        }
        try {
            assertTrue(pipelined, "The next logical batch should start while an earlier search is still running");
            assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5), results.get(5, TimeUnit.SECONDS));
        } finally {
            searchExecutor.shutdownNow();
            coordinator.shutdownNow();
        }
    }

    private void assertParallelMatchesSequential(List<List<RouteRequest>> requests,
            List<FeatureIndex.IndexedFeature> inputs, int maximumCombinations) {
        List<String> inputGeometrySnapshot = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : inputs) inputGeometrySnapshot.add(input.getGeometry().toText());

        MultiTargetRouteResult sequential = router.route(requests, inputs, maximumCombinations);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        MultiTargetRouteResult parallel;
        try {
            parallel = router.route(requests, inputs, maximumCombinations, executor, 4);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(sequential.getStatus(), parallel.getStatus());
        assertEquals(sequential.getUnroutedTargetIds(), parallel.getUnroutedTargetIds());
        assertEquals(sequential.getUnsearchedTargetIds(), parallel.getUnsearchedTargetIds());
        assertEquals(new ArrayList<>(sequential.getTargetAttempts().keySet()),
                new ArrayList<>(parallel.getTargetAttempts().keySet()));
        assertEquals(sequential.getTargetAttempts(), parallel.getTargetAttempts());
        assertEquals(sequential.getCombinationsEvaluated(), parallel.getCombinationsEvaluated());
        assertEquals(sequential.isCombinationLimitReached(), parallel.isCombinationLimitReached());
        assertEquals(candidateSignatures(sequential), candidateSignatures(parallel));

        List<String> geometryAfterSearch = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : inputs) geometryAfterSearch.add(input.getGeometry().toText());
        assertEquals(inputGeometrySnapshot, geometryAfterSearch);
    }

    private static List<String> candidateSignatures(MultiTargetRouteResult result) {
        List<String> signatures = new ArrayList<>();
        for (ru.lct.heatnetwork.network.RouteTreeOptimizer.TreeCandidate candidate : result.getCandidates()) {
            StringBuilder signature = new StringBuilder(candidate.getVariant().getId());
            for (ru.lct.heatnetwork.domain.Variant.Node node : candidate.getVariant().getNodes()) {
                signature.append("|N:").append(node.getId()).append(':').append(node.getX()).append(':').append(node.getY());
            }
            for (ru.lct.heatnetwork.domain.Variant.Segment segment : candidate.getVariant().getSegments()) {
                signature.append("|S:").append(segment.getId()).append(':').append(segment.getGeometry().toText());
            }
            signature.append("|L:").append(candidate.getTotalLengthM());
            signatures.add(signature.toString());
        }
        return signatures;
    }

    private RouteTreeOptimizer.TreeCandidate forest(String name,
            List<FeatureIndex.IndexedFeature> inputs, int... targetNumbers) {
        List<Variant> routes = new ArrayList<>();
        List<AttachmentCandidate> attachments = new ArrayList<>();
        for (int number : targetNumbers) {
            int x = number * 30;
            TypedId targetId = TypedId.of(BigDecimal.valueOf(number));
            TypedId networkId = TypedId.of("network-" + number);
            TypedId chamberId = TypedId.of("chamber-" + number);
            String prefix = name + ':' + number;
            LineString line = FACTORY.createLineString(new Coordinate[] {
                    new Coordinate(x, 0), new Coordinate(x + 5, 0)});
            routes.add(new Variant(prefix, Arrays.asList(
                    new Variant.Node(prefix + ":target", x, 0, "oks_connection_point", targetId),
                    new Variant.Node(prefix + ":root", x + 5, 0, "heat_chamber", chamberId)),
                    Collections.singletonList(new Variant.Segment(prefix + ":pipe",
                            prefix + ":target", prefix + ":root", line,
                            prefix + ":target", prefix + ":root", BigDecimal.ONE, 50,
                            "base", Collections.emptySet(), BigDecimal.ONE))));
            attachments.add(attachment(networkId, chamberId, point(x + 5, 0)));
        }
        RouteTreeOptimizer.OptimizationResult result = new RouteTreeOptimizer().optimize(name,
                routes, attachments, inputs);
        assertTrue(result.isFound(), () -> result.getDetail() + (result.getValidation() == null ? ""
                : result.getValidation().getIssues()));
        return result.getCandidate();
    }

    private static Fixture fixture(boolean includeBlocked) throws Exception {
        TypedId firstId = TypedId.of(new BigDecimal("1"));
        TypedId secondId = TypedId.of("1");
        TypedId firstNetworkId = TypedId.of("network-a");
        TypedId secondNetworkId = TypedId.of("network-b");
        TypedId firstChamberId = TypedId.of("chamber-a");
        TypedId secondChamberId = TypedId.of("chamber-b");
        Point firstPoint = point(-5, 2);
        Point secondPoint = point(-5, 18);
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        FeatureIndex.IndexedFeature first = target(firstId, 2.0, firstPoint);
        FeatureIndex.IndexedFeature second = target(secondId, 3.0, secondPoint);
        inputs.add(first);
        inputs.add(second);
        inputs.add(indexed(properties(TypedId.of("building"), "restriction", "restriction_type", "oks"),
                geometry("POLYGON ((-10 -5, 0 -5, 0 25, -10 25, -10 -5))")));
        FeatureIndex.IndexedFeature firstNetwork = indexed(properties(firstNetworkId, "heat_network", "diameter", 500),
                geometry("LINESTRING (15 0, 30 0)"));
        FeatureIndex.IndexedFeature secondNetwork = indexed(properties(secondNetworkId, "heat_network", "diameter", 500),
                geometry("LINESTRING (15 20, 30 20)"));
        inputs.add(firstNetwork);
        inputs.add(secondNetwork);
        inputs.add(indexed(new FeatureProperties(firstChamberId, "heat_chamber"), point(15, 0)));
        inputs.add(indexed(new FeatureProperties(secondChamberId, "heat_chamber"), point(15, 20)));

        TypedId blockedId = TypedId.of("blocked");
        FeatureIndex.IndexedFeature blocked = null;
        if (includeBlocked) {
            blocked = target(blockedId, 1.0, point(40, 0));
            inputs.add(blocked);
            inputs.add(indexed(properties(TypedId.of("wall"), "restriction", "restriction_type", "park"),
                    geometry("POLYGON ((34 -30, 36 -30, 36 30, 34 30, 34 -30))")));
        }

        List<List<RouteRequest>> requests = new ArrayList<>();
        requests.add(Collections.singletonList(request("target-a", first, inputs,
                attachment(firstNetworkId, firstChamberId, point(15, 0)), new Envelope(-12, 32, -10, 10))));
        requests.add(Collections.singletonList(request("target-b", second, inputs,
                attachment(secondNetworkId, secondChamberId, point(15, 20)), new Envelope(-12, 32, 10, 30))));
        if (includeBlocked) {
            requests.add(Collections.singletonList(request("target-c", blocked, inputs,
                    attachment(firstNetworkId, firstChamberId, point(15, 0)), new Envelope(0, 45, -15, 15))));
        }
        return new Fixture(requests, inputs, blockedId);
    }

    private static RouteRequest request(String id, FeatureIndex.IndexedFeature target,
            List<FeatureIndex.IndexedFeature> inputs, AttachmentCandidate attachment, Envelope area) {
        return new RouteRequest(id, target, attachment, inputs, area, 300);
    }

    private static AttachmentCandidate attachment(TypedId networkId, TypedId chamberId, Point point) {
        return new AttachmentCandidate(networkId, 0, point, point,
                AttachmentCandidate.EndpointKind.EXISTING_CHAMBER, chamberId,
                Collections.singletonList(networkId), 1, 500);
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
    private static Geometry geometry(String wkt) throws Exception { return new WKTReader(FACTORY).read(wkt); }

    private static final class Fixture {
        private final List<List<RouteRequest>> requests;
        private final List<FeatureIndex.IndexedFeature> inputs;
        private final TypedId blockedTargetId;
        private Fixture(List<List<RouteRequest>> requests, List<FeatureIndex.IndexedFeature> inputs, TypedId blockedTargetId) {
            this.requests = requests;
            this.inputs = inputs;
            this.blockedTargetId = blockedTargetId;
        }
    }
}
