package ru.lct.heatnetwork.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnetwork.costing.VariantCostCalculator;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.TypedIdOrder;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.network.RouteTreeOptimizer;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.spatial.ClearanceCheck;
import ru.lct.heatnetwork.spatial.FeatureIndex;

/** Routes every reachable target, then evaluates bounded combinations of per-target choices. */
public final class MultiTargetRouter {
    private static final Logger LOG = LoggerFactory.getLogger(MultiTargetRouter.class);
    private static final int MAX_DIRECT_BRANCH_ATTEMPTS = 512;
    private static final int MAX_NAVIGATOR_BRANCH_ATTEMPTS = 800;
    private static final int MAX_NAVIGATOR_SEEDS = 3;
    private static final int MAX_NAVIGATOR_ATTEMPTS_PER_SEED = 80;
    private static final int MAX_NAVIGATOR_CONTROL_ATTEMPTS = 240;
    private static final int MAX_NAVIGATOR_GOALS_PER_VISIT = 12;
    private static final int MAX_GROUP_SEEDS = 5;
    private static final int MAX_GROUP_PAIRS = 24;
    private static final int MAX_GROUP_ANCHOR_GOALS = 2;
    private static final int MAX_GROUP_BRANCH_GOALS = 3;
    private static final int MAX_GROUP_ATTEMPTS_PER_STEP = 40;
    private static final int MAX_GROUP_CONTROL_ATTEMPTS = 160;
    private static final int MAX_PORTFOLIO_SEEDS = 2;
    private static final int MAX_PORTFOLIO_ATTEMPTS = 40;
    private static final int MAX_INITIAL_SEED_ATTEMPTS = 12;
    private static final int MAX_INITIAL_SEED_TARGETS = 3;
    private static final int MAX_GROUP_LOOKAHEAD_TARGETS = 2;
    private static final int MAX_GROUP_LOOKAHEAD_GOALS_PER_TARGET = 1;
    private static final int MAX_GROUP_CONTINUATIONS = 3;
    private final SingleTargetRouter singleTargetRouter;
    private final RouteTreeOptimizer optimizer;
    private final SingleTargetRouter.SearchMode searchMode;
    private final boolean groupFocusedRouting;

    public MultiTargetRouter() {
        this(SingleTargetRouter.SearchMode.VISIBILITY);
    }

    public MultiTargetRouter(SingleTargetRouter.SearchMode searchMode) {
        this(searchMode, false);
    }

    public MultiTargetRouter(SingleTargetRouter.SearchMode searchMode, boolean groupFocusedRouting) {
        this(new SingleTargetRouter(searchMode), new RouteTreeOptimizer(), searchMode, groupFocusedRouting);
    }

    public MultiTargetRouter(SingleTargetRouter singleTargetRouter, RouteTreeOptimizer optimizer) {
        this(singleTargetRouter, optimizer, SingleTargetRouter.SearchMode.VISIBILITY, false);
    }

    private MultiTargetRouter(SingleTargetRouter singleTargetRouter, RouteTreeOptimizer optimizer,
            SingleTargetRouter.SearchMode searchMode, boolean groupFocusedRouting) {
        if (singleTargetRouter == null || optimizer == null) throw new IllegalArgumentException("Router and optimizer are required");
        if (searchMode == null) throw new IllegalArgumentException("Route search mode is required");
        this.singleTargetRouter = singleTargetRouter;
        this.optimizer = optimizer;
        this.searchMode = searchMode;
        this.groupFocusedRouting = groupFocusedRouting;
    }

    /** Each inner list contains alternative attachment requests for one distinct target. */
    public MultiTargetRouteResult route(List<List<RouteRequest>> alternativesByTarget,
            List<FeatureIndex.IndexedFeature> validationInputs, int maximumCombinations) {
        return route(alternativesByTarget, validationInputs, maximumCombinations, null, 1);
    }

    /** Searches independent requests in bounded batches and collects each batch in input order. */
    public MultiTargetRouteResult route(List<List<RouteRequest>> alternativesByTarget,
            List<FeatureIndex.IndexedFeature> validationInputs, int maximumCombinations,
            ExecutorService searchExecutor, int maximumParallelSearches) {
        return route(alternativesByTarget, validationInputs, maximumCombinations, searchExecutor,
                maximumParallelSearches, RouteProgressListener.NONE);
    }

    public MultiTargetRouteResult route(List<List<RouteRequest>> alternativesByTarget,
            List<FeatureIndex.IndexedFeature> validationInputs, int maximumCombinations,
            ExecutorService searchExecutor, int maximumParallelSearches, RouteProgressListener progressListener) {
        return route(alternativesByTarget, validationInputs, maximumCombinations, searchExecutor,
                maximumParallelSearches, progressListener, Collections.emptyList(), null, 0);
    }

    /** The extra target list is used only by the benchmark navigator after a valid seed tree exists. */
    public MultiTargetRouteResult route(List<List<RouteRequest>> alternativesByTarget,
            List<FeatureIndex.IndexedFeature> validationInputs, int maximumCombinations,
            ExecutorService searchExecutor, int maximumParallelSearches, RouteProgressListener progressListener,
            List<FeatureIndex.IndexedFeature> treeGrowthTargets, Envelope searchArea,
            int maximumVisibilityVertices) {
        if (alternativesByTarget == null || alternativesByTarget.isEmpty() || validationInputs == null
                || maximumCombinations < 1 || maximumParallelSearches < 1 || maximumParallelSearches > 8
                || (searchExecutor == null && maximumParallelSearches != 1)) {
            return invalid("At least one target, validation inputs and a positive combination limit are required");
        }
        Map<TypedId, List<TypedId>> districtMembersByAnchor = new HashMap<>();
        if (searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR) {
            alternativesByTarget = withDistrictSeedCorridors(alternativesByTarget, treeGrowthTargets,
                    validationInputs, searchArea, districtMembersByAnchor);
        }
        RouteProgressListener listener = progressListener == null ? RouteProgressListener.NONE : progressListener;
        List<TargetChoices> choices = new ArrayList<>();
        Set<TypedId> targetIds = new HashSet<>();
        Set<String> requestIds = new HashSet<>();
        List<TypedId> targetIdsByIndex = new ArrayList<>();
        List<List<RouteCandidate>> candidatesByTarget = new ArrayList<>();
        List<SearchTask> searchTasks = new ArrayList<>();
        Map<TypedId, Integer> initialSeedRank = new HashMap<>();
        Map<TypedId, List<RouteCandidate.Status>> attempts = new LinkedHashMap<>();
        List<TypedId> unrouted = new ArrayList<>();
        List<TypedId> unsearched = new ArrayList<>();
        Map<TypedId, String> initialUnsearchedReasons = new LinkedHashMap<>();
        boolean routeSearchIncomplete = false;

        for (List<RouteRequest> alternatives : alternativesByTarget) {
            if (alternatives == null || alternatives.isEmpty() || alternatives.get(0) == null
                    || alternatives.get(0).getTarget() == null
                    || alternatives.get(0).getTarget().getFeature().getId() == null) {
                return invalid("Every target must have at least one request with a typed input ID");
            }
            TypedId targetId = alternatives.get(0).getTarget().getFeature().getId();
            if (!targetIds.add(targetId)) return invalid("Each target must have its own alternatives group");
            int targetIndex = targetIdsByIndex.size();
            targetIdsByIndex.add(targetId);
        List<RouteCandidate> targetCandidates = new ArrayList<>(Collections.nCopies(alternatives.size(), null));
            candidatesByTarget.add(targetCandidates);
            for (int alternativeIndex = 0; alternativeIndex < alternatives.size(); alternativeIndex++) {
                RouteRequest request = alternatives.get(alternativeIndex);
                if (request == null || request.getTarget() == null || !targetId.equals(request.getTarget().getFeature().getId())
                        || !requestIds.add(request.getVariantId())) {
                    return invalid("Alternative requests must belong to one target and have unique variant IDs");
                }
                RouteRequest localRequest = forSingleTarget(request, targetId);
                searchTasks.add(new SearchTask(targetIndex, alternativeIndex, localRequest));
            }
        }

        if (searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR) {
            List<SearchTask> seedTasks = orderedSeedTasks(searchTasks, treeGrowthTargets);
            for (SearchTask task : seedTasks) {
                initialSeedRank.putIfAbsent(task.request.getTarget().getFeature().getId(),
                        initialSeedRank.size());
            }
            Set<Integer> foundSeedTargets = new HashSet<>();
            int searched = 0;
            ObstacleBufferCache bufferCache = new ObstacleBufferCache();
            try {
                for (SearchTask task : seedTasks) {
                    if (foundSeedTargets.size() >= MAX_INITIAL_SEED_TARGETS
                            || searched >= MAX_INITIAL_SEED_ATTEMPTS) break;
                    if (foundSeedTargets.contains(task.targetIndex)) continue;
                    RouteCandidate candidate = singleTargetRouter.route(task.request, bufferCache);
                    candidatesByTarget.get(task.targetIndex).set(task.alternativeIndex, candidate);
                    notifyAttempt(listener, task.request, candidate);
                    searched++;
                    if (candidate.isFound()) foundSeedTargets.add(task.targetIndex);
                }
                Set<Integer> retriedTargets = new HashSet<>();
                for (SearchTask task : seedTasks) {
                    if (searched >= MAX_INITIAL_SEED_ATTEMPTS) break;
                    if (!foundSeedTargets.contains(task.targetIndex)
                            || candidatesByTarget.get(task.targetIndex).get(task.alternativeIndex) != null
                            || !retriedTargets.add(task.targetIndex)) continue;
                    RouteCandidate candidate = singleTargetRouter.route(task.request, bufferCache);
                    candidatesByTarget.get(task.targetIndex).set(task.alternativeIndex, candidate);
                    notifyAttempt(listener, task.request, candidate);
                    searched++;
                }
            } finally {
                bufferCache.clear();
            }
            LOG.info("Navigator seed search completed realAttempts={} availableTasks={} distinctSeedTargets={} truncated={}",
                    searched, searchTasks.size(), foundSeedTargets.size(), searched < searchTasks.size());
        } else if (searchExecutor == null) {
            for (int offset = 0; offset < searchTasks.size(); offset += maximumParallelSearches) {
                int end = Math.min(searchTasks.size(), offset + maximumParallelSearches);
                ObstacleBufferCache bufferCache = new ObstacleBufferCache();
                for (int taskIndex = offset; taskIndex < end; taskIndex++) {
                    SearchTask task = searchTasks.get(taskIndex);
                    RouteCandidate candidate = singleTargetRouter.route(task.request, bufferCache);
                    candidatesByTarget.get(task.targetIndex).set(task.alternativeIndex, candidate);
                    notifyAttempt(listener, task.request, candidate);
                }
            }
        } else {
            int batchCount = (searchTasks.size() + maximumParallelSearches - 1) / maximumParallelSearches;
            List<ObstacleBufferCache> batchCaches = new ArrayList<>(batchCount);
            int[] remainingByBatch = new int[batchCount];
            for (int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
                batchCaches.add(new ObstacleBufferCache());
                remainingByBatch[batchIndex] = Math.min(maximumParallelSearches,
                        searchTasks.size() - batchIndex * maximumParallelSearches);
            }
            List<Callable<RouteCandidate>> searchCalls = new ArrayList<>(searchTasks.size());
            for (int taskIndex = 0; taskIndex < searchTasks.size(); taskIndex++) {
                SearchTask task = searchTasks.get(taskIndex);
                ObstacleBufferCache bufferCache = batchCaches.get(taskIndex / maximumParallelSearches);
                searchCalls.add(() -> {
                    RouteCandidate candidate = singleTargetRouter.route(task.request, bufferCache);
                    notifyAttempt(listener, task.request, candidate);
                    return candidate;
                });
            }
            List<RouteCandidate> results;
            try {
                results = executeSlidingWindow(searchCalls, searchExecutor, maximumParallelSearches, taskIndex -> {
                    int batchIndex = taskIndex / maximumParallelSearches;
                    if (--remainingByBatch[batchIndex] == 0) batchCaches.get(batchIndex).clear();
                });
            } finally {
                for (ObstacleBufferCache cache : batchCaches) cache.clear();
            }
            for (int taskIndex = 0; taskIndex < searchTasks.size(); taskIndex++) {
                SearchTask task = searchTasks.get(taskIndex);
                candidatesByTarget.get(task.targetIndex).set(task.alternativeIndex, results.get(taskIndex));
            }
        }
        for (int targetIndex = 0; targetIndex < alternativesByTarget.size(); targetIndex++) {
            List<RouteRequest> alternatives = alternativesByTarget.get(targetIndex);
            TypedId targetId = targetIdsByIndex.get(targetIndex);
            List<RouteChoice> found = new ArrayList<>();
            List<RouteCandidate.Status> targetStatuses = new ArrayList<>();
            List<RouteCandidate> targetCandidates = candidatesByTarget.get(targetIndex);
            for (int alternativeIndex = 0; alternativeIndex < targetCandidates.size(); alternativeIndex++) {
                RouteCandidate candidate = targetCandidates.get(alternativeIndex);
                RouteRequest request = alternatives.get(alternativeIndex);
                if (candidate == null) {
                    targetStatuses.add(RouteCandidate.Status.SEED_SEARCH_SKIPPED);
                    continue;
                }
                targetStatuses.add(candidate.getStatus());
                if (candidate.getStatus() == RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED
                        || candidate.getStatus() == RouteCandidate.Status.SEARCH_INCOMPLETE
                        || candidate.getStatus() == RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH) {
                    routeSearchIncomplete = true;
                }
                if (candidate.isFound()) found.add(new RouteChoice(candidate.getVariant(), request.getAttachment()));
            }
            attempts.put(targetId, targetStatuses);
            if (found.isEmpty()) {
                if (targetStatuses.contains(RouteCandidate.Status.SEED_SEARCH_SKIPPED)) {
                    unsearched.add(targetId);
                    initialUnsearchedReasons.put(targetId, "SEED_SEARCH_SKIPPED");
                } else if (targetStatuses.contains(RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED)
                        || targetStatuses.contains(RouteCandidate.Status.SEARCH_INCOMPLETE)) {
                    unsearched.add(targetId);
                    initialUnsearchedReasons.put(targetId, "INITIAL_ROUTE_SEARCH_LIMIT");
                } else {
                    unrouted.add(targetId);
                }
            }
            else choices.add(new TargetChoices(targetId, alternatives.get(0).getTarget(), found));
        }

        if (choices.isEmpty()) {
            MultiTargetRouteResult.Status status = unsearched.isEmpty()
                    ? MultiTargetRouteResult.Status.NO_TARGET_ROUTED
                    : MultiTargetRouteResult.Status.SEARCH_LIMIT_REACHED;
            String detail = unsearched.isEmpty()
                    ? "No target produced an accepted single-target route within the supplied searches"
                    : "The route search stopped at a configured limit; unsearched targets are not classified as unconnected";
            return new MultiTargetRouteResult(status, Collections.emptyList(), unrouted, unsearched,
                    attempts, 0, false, detail);
        }

        notifyCombinationStarted(listener);
        List<RouteTreeOptimizer.TreeCandidate> candidates;
        Map<String, Map<TypedId, String>> unsearchedReasonsByCandidate = new LinkedHashMap<>();
        SearchCounter counter;
        boolean topologyOmission = false;
        if (searchMode == SingleTargetRouter.SearchMode.ADAPTIVE_QUADTREE
                || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_FOREST
                || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_EXHAUSTIVE
                || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_DIRECT_BRANCH
                || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_SPATIAL
                || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR) {
            AdaptiveCombinationResult forests = searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_EXHAUSTIVE
                    || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_DIRECT_BRANCH
                    || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_SPATIAL
                    || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR
                    ? combineExhaustive(choices, validationInputs, maximumCombinations)
                    : combineAdaptive(choices, validationInputs, maximumCombinations);
            candidates = forests.candidates;
            counter = forests.counter;
            if (searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR) {
                for (RouteTreeOptimizer.TreeCandidate candidate : candidates) {
                    Map<TypedId, String> reasons = new LinkedHashMap<>();
                    Set<TypedId> connected = representedTargets(candidate.getVariant());
                    for (TypedId targetId : unsearched) {
                        if (!connected.contains(targetId)) reasons.put(targetId,
                                initialUnsearchedReasons.getOrDefault(targetId, "INITIAL_ROUTE_SEARCH_LIMIT"));
                    }
                    unsearchedReasonsByCandidate.put(candidate.getVariant().getId(), reasons);
                }
            }
            if ((searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_DIRECT_BRANCH
                    || searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_SPATIAL) && !candidates.isEmpty()) {
                candidates.sort(Comparator
                        .comparingInt((RouteTreeOptimizer.TreeCandidate candidate) ->
                                connectedTargetCount(candidate.getVariant())).reversed()
                        .thenComparingDouble(RouteTreeOptimizer.TreeCandidate::getTotalLengthM)
                        .thenComparing(candidate -> candidate.getVariant().getId()));
                List<RouteTreeOptimizer.TreeCandidate> seeds = new ArrayList<>();
                Set<Set<TypedId>> seedCoverages = new HashSet<>();
                for (RouteTreeOptimizer.TreeCandidate candidate : candidates) {
                    Set<TypedId> coverage = representedTargets(candidate.getVariant());
                    if (seedCoverages.add(coverage)) seeds.add(candidate);
                    if (seeds.size() >= MAX_NAVIGATOR_SEEDS) break;
                }
                int branchAttempts = 0;
                int attached = 0;
                int grownSeeds = 0;
                Map<String, Integer> rejected = new TreeMap<>();
                for (RouteTreeOptimizer.TreeCandidate seed : seeds) {
                    if (branchAttempts >= MAX_DIRECT_BRANCH_ATTEMPTS) break;
                    BranchGrowth growth = growDirectBranches(seed, choices, validationInputs,
                            MAX_DIRECT_BRANCH_ATTEMPTS - branchAttempts);
                    branchAttempts += growth.attempted;
                    attached += growth.attached;
                    for (Map.Entry<String, Integer> reason : growth.rejected.entrySet()) {
                        rejected.merge(reason.getKey(), reason.getValue(), Integer::sum);
                    }
                    if (growth.candidate != null) {
                        candidates.add(growth.candidate);
                        grownSeeds++;
                    }
                }
                LOG.info("Direct trunk branch seeds={} attempted={} attached={} grownSeeds={} budgetReached={} rejected={}",
                        seeds.size(), branchAttempts, attached, grownSeeds,
                        branchAttempts >= MAX_DIRECT_BRANCH_ATTEMPTS, rejected);
            }
            if (searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR
                    && !candidates.isEmpty()) {
                if (treeGrowthTargets == null || searchArea == null || maximumVisibilityVertices < 2) {
                    return invalid("Tree navigator requires every in-area target and the projected search bounds");
                }
                candidates.sort(Comparator
                        .comparingInt((RouteTreeOptimizer.TreeCandidate candidate) ->
                                connectedTargetCount(candidate.getVariant())).reversed()
                        .thenComparingDouble(RouteTreeOptimizer.TreeCandidate::getTotalLengthM)
                        .thenComparing(candidate -> candidate.getVariant().getId()));
                List<RouteTreeOptimizer.TreeCandidate> rawSeeds = new ArrayList<>(
                        candidates.subList(0, Math.min(MAX_NAVIGATOR_SEEDS, candidates.size())));
                List<RouteTreeOptimizer.TreeCandidate> seeds = new ArrayList<>();
                Set<Set<TypedId>> seedCoverages = new HashSet<>();
                for (RouteTreeOptimizer.TreeCandidate candidate : candidates) {
                    Set<TypedId> coverage = representedTargets(candidate.getVariant());
                    if (seedCoverages.add(coverage)) seeds.add(candidate);
                    if (seeds.size() >= MAX_NAVIGATOR_SEEDS) break;
                }
                List<RouteTreeOptimizer.TreeCandidate> groupSeeds = new ArrayList<>();
                for (RouteTreeOptimizer.TreeCandidate candidate : candidates) {
                    if (representedTargets(candidate.getVariant()).size() == 1) groupSeeds.add(candidate);
                }
                Map<RouteTreeOptimizer.TreeCandidate, FutureSeedOpportunity> futureSeedOpportunities =
                        new HashMap<>();
                for (RouteTreeOptimizer.TreeCandidate candidate : groupSeeds) {
                    futureSeedOpportunities.put(candidate, futureSeedOpportunity(candidate.getVariant(),
                            treeGrowthTargets, validationInputs, searchArea));
                }
                groupSeeds.sort(Comparator
                        .comparingInt((RouteTreeOptimizer.TreeCandidate candidate) -> {
                            TypedId id = representedTargets(candidate.getVariant()).iterator().next();
                            return initialSeedRank.getOrDefault(id, Integer.MAX_VALUE);
                        })
                        .thenComparing((left, right) -> Integer.compare(
                                futureSeedOpportunities.get(right).safeTargetCount,
                                futureSeedOpportunities.get(left).safeTargetCount))
                        .thenComparing((left, right) -> Double.compare(
                                futureSeedOpportunities.get(right).proximity,
                                futureSeedOpportunities.get(left).proximity))
                        .thenComparingDouble(RouteTreeOptimizer.TreeCandidate::getTotalLengthM)
                        .thenComparing(candidate -> candidate.getVariant().getId()));
                groupSeeds = diverseGroupSeeds(groupSeeds);
                if (groupSeeds.isEmpty()) groupSeeds.addAll(seeds);
                if (groupFocusedRouting) {
                    Set<Set<TypedId>> groupedCoverages = new HashSet<>();
                    for (RouteTreeOptimizer.TreeCandidate seed : groupSeeds) {
                        groupedCoverages.add(representedTargets(seed.getVariant()));
                    }
                    for (RouteTreeOptimizer.TreeCandidate candidate : candidates) {
                        Set<TypedId> coverage = representedTargets(candidate.getVariant());
                        if (coverage.size() > 1 && hasMultipleRoots(candidate.getVariant())
                                && groupedCoverages.add(coverage)) {
                            if (groupSeeds.size() >= MAX_GROUP_SEEDS) groupSeeds.remove(groupSeeds.size() - 1);
                            groupSeeds.add(candidate);
                            break;
                        }
                    }
                }
                try {
                    listener.onTreeGrowthStarted();
                } catch (RuntimeException exception) {
                    LOG.warn("Tree-growth progress observer failed; routing will continue", exception);
                }
                int attempted = 0, directAttempted = 0, attached = 0, grownSeeds = 0;
                Map<String, Integer> rejected = new TreeMap<>();
                List<RouteTreeOptimizer.TreeCandidate> completedGroups = new ArrayList<>();
                List<RouteTreeOptimizer.TreeCandidate> donorTrees = new ArrayList<>();
                for (BranchChoicePolicy policy : BranchChoicePolicy.values()) {
                    if (groupFocusedRouting && policy == BranchChoicePolicy.SHORTEST_VALID) continue;
                    if (policy == BranchChoicePolicy.GROUP_LOOKAHEAD) {
                        List<RouteTreeOptimizer.TreeCandidate> portfolioSeeds = new ArrayList<>();
                        List<NavigatorGrowthSpec> groupSpecs = new ArrayList<>();
                        for (int seedIndex = 0; seedIndex < Math.min(MAX_GROUP_SEEDS, groupSeeds.size()); seedIndex++) {
                            RouteTreeOptimizer.TreeCandidate groupSeed = groupSeeds.get(seedIndex);
                            Set<TypedId> anchorIds = representedTargets(groupSeed.getVariant());
                            List<TypedId> sectionIds = anchorIds.size() == 1
                                    ? districtMembersByAnchor.getOrDefault(anchorIds.iterator().next(),
                                            Collections.emptyList()) : Collections.emptyList();
                            List<FeatureIndex.IndexedFeature> sectionTargets = new ArrayList<>();
                            for (FeatureIndex.IndexedFeature target : treeGrowthTargets) {
                                if (sectionIds.contains(target.getFeature().getId())) sectionTargets.add(target);
                            }
                            groupSpecs.add(new NavigatorGrowthSpec(groupSeeds.get(seedIndex),
                                    policy, "group:seed" + seedIndex,
                                    seedIndex == 0 ? MAX_GROUP_CONTROL_ATTEMPTS
                                            : seedIndex < MAX_INITIAL_SEED_TARGETS
                                                    ? MAX_NAVIGATOR_ATTEMPTS_PER_SEED : 40, true,
                                    sectionTargets));
                        }
                        List<NavigatorGrowthResult> groupResults = growNavigatorStage(groupSpecs,
                                treeGrowthTargets, validationInputs, searchArea, maximumVisibilityVertices,
                                MAX_NAVIGATOR_BRANCH_ATTEMPTS - attempted, searchExecutor,
                                maximumParallelSearches, listener);
                        for (int groupIndex = 0; groupIndex < groupResults.size(); groupIndex++) {
                            NavigatorGrowthResult scenario = groupResults.get(groupIndex);
                            BranchGrowth growth = scenario.growth;
                            mergeNavigatorAttempts(attempts, scenario.attempts);
                            attempted += growth.attempted;
                            attached += growth.attached;
                            routeSearchIncomplete |= growth.searchIncomplete;
                            for (Map.Entry<String, Integer> reason : growth.rejected.entrySet()) {
                                rejected.merge(reason.getKey(), reason.getValue(), Integer::sum);
                            }
                            if (growth.candidate != null) {
                                candidates.add(growth.candidate);
                                completedGroups.add(growth.candidate);
                                Map<TypedId, String> candidateReasons = new LinkedHashMap<>();
                                Set<TypedId> connected = representedTargets(growth.candidate.getVariant());
                                for (TypedId targetId : unsearched) {
                                    if (!connected.contains(targetId)) {
                                        candidateReasons.put(targetId, initialUnsearchedReasons.getOrDefault(
                                                targetId, "INITIAL_ROUTE_SEARCH_LIMIT"));
                                    }
                                }
                                candidateReasons.putAll(growth.unsearchedReasons);
                                unsearchedReasonsByCandidate.put(growth.candidate.getVariant().getId(),
                                        candidateReasons);
                                grownSeeds++;
                            } else if (groupFocusedRouting) {
                                completedGroups.add(groupSpecs.get(groupIndex).seed);
                            }
                            for (RouteTreeOptimizer.TreeCandidate portfolioSeed : growth.portfolioSeeds) {
                                if (portfolioSeeds.size() >= MAX_PORTFOLIO_SEEDS) break;
                                portfolioSeeds.add(portfolioSeed);
                            }
                        }
                        int portfolioAttempted = 0;
                        List<NavigatorGrowthSpec> portfolioSpecs = new ArrayList<>();
                        for (int sideIndex = 0; sideIndex < portfolioSeeds.size(); sideIndex++) {
                            portfolioSpecs.add(new NavigatorGrowthSpec(portfolioSeeds.get(sideIndex),
                                    BranchChoicePolicy.SHORTEST_VALID, "portfolio:seed" + sideIndex,
                                    Math.min(20, MAX_PORTFOLIO_ATTEMPTS - sideIndex * 20), true,
                                    Collections.emptyList()));
                        }
                        List<NavigatorGrowthResult> portfolioResults = growNavigatorStage(portfolioSpecs,
                                treeGrowthTargets, validationInputs, searchArea, maximumVisibilityVertices,
                                MAX_NAVIGATOR_BRANCH_ATTEMPTS - attempted, searchExecutor,
                                maximumParallelSearches, listener);
                        for (NavigatorGrowthResult scenario : portfolioResults) {
                            BranchGrowth side = scenario.growth;
                            mergeNavigatorAttempts(attempts, scenario.attempts);
                            attempted += side.attempted;
                            portfolioAttempted += side.attempted;
                            directAttempted += side.directAttempted;
                            attached += side.attached;
                            routeSearchIncomplete |= side.searchIncomplete;
                            side.rejected.forEach((reason, count) -> rejected.merge(reason, count, Integer::sum));
                            if (side.candidate != null) {
                                candidates.add(side.candidate);
                                if (groupFocusedRouting) donorTrees.add(side.candidate);
                                Map<TypedId, String> candidateReasons = new LinkedHashMap<>();
                                Set<TypedId> connected = representedTargets(side.candidate.getVariant());
                                for (TypedId targetId : unsearched) {
                                    if (!connected.contains(targetId)) {
                                        candidateReasons.put(targetId, initialUnsearchedReasons.getOrDefault(
                                                targetId, "INITIAL_ROUTE_SEARCH_LIMIT"));
                                    }
                                }
                                candidateReasons.putAll(side.unsearchedReasons);
                                unsearchedReasonsByCandidate.put(side.candidate.getVariant().getId(),
                                        candidateReasons);
                                grownSeeds++;
                            }
                        }
                        continue;
                    }
                    List<RouteTreeOptimizer.TreeCandidate> policySeeds = policy == BranchChoicePolicy.FIRST_VALID
                            ? rawSeeds : seeds;
                    List<NavigatorGrowthSpec> policySpecs = new ArrayList<>();
                    for (int seedIndex = 0; seedIndex < policySeeds.size(); seedIndex++) {
                        String trajectoryId = policy.name().toLowerCase(java.util.Locale.ROOT)
                                + ":seed" + seedIndex;
                        int trajectoryBudget = groupFocusedRouting
                                ? 40 : policy == BranchChoicePolicy.FIRST_VALID && seedIndex == 0
                                        ? MAX_NAVIGATOR_CONTROL_ATTEMPTS : MAX_NAVIGATOR_ATTEMPTS_PER_SEED;
                        policySpecs.add(new NavigatorGrowthSpec(policySeeds.get(seedIndex),
                                policy, trajectoryId, trajectoryBudget, true, Collections.emptyList()));
                    }
                    List<NavigatorGrowthResult> policyResults = growNavigatorStage(policySpecs,
                            treeGrowthTargets, validationInputs, searchArea, maximumVisibilityVertices,
                            MAX_NAVIGATOR_BRANCH_ATTEMPTS - attempted, searchExecutor,
                            maximumParallelSearches, listener);
                    for (NavigatorGrowthResult scenario : policyResults) {
                        BranchGrowth growth = scenario.growth;
                        mergeNavigatorAttempts(attempts, scenario.attempts);
                        attempted += growth.attempted;
                        directAttempted += growth.directAttempted;
                        attached += growth.attached;
                        routeSearchIncomplete |= growth.searchIncomplete;
                        for (Map.Entry<String, Integer> reason : growth.rejected.entrySet()) {
                            rejected.merge(reason.getKey(), reason.getValue(), Integer::sum);
                        }
                        if (growth.candidate != null) {
                            candidates.add(growth.candidate);
                            if (groupFocusedRouting && policy == BranchChoicePolicy.FIRST_VALID) {
                                donorTrees.add(growth.candidate);
                            }
                            Map<TypedId, String> candidateReasons = new LinkedHashMap<>();
                            Set<TypedId> connected = representedTargets(growth.candidate.getVariant());
                            for (TypedId targetId : unsearched) {
                                if (!connected.contains(targetId)) {
                                    candidateReasons.put(targetId, initialUnsearchedReasons.getOrDefault(
                                            targetId, "INITIAL_ROUTE_SEARCH_LIMIT"));
                                }
                            }
                            candidateReasons.putAll(growth.unsearchedReasons);
                            unsearchedReasonsByCandidate.put(growth.candidate.getVariant().getId(),
                                    candidateReasons);
                            grownSeeds++;
                        }
                    }
                }
                if (groupFocusedRouting && !completedGroups.isEmpty()
                        && attempted < MAX_NAVIGATOR_BRANCH_ATTEMPTS) {
                    List<AttachmentCandidate> originalAttachments = new ArrayList<>();
                    for (TargetChoices alternatives : choices) {
                        for (RouteChoice choice : alternatives.routes) originalAttachments.add(choice.attachment);
                    }
                    List<RouteTreeOptimizer.TreeCandidate> joint = combineGroupAlternatives(
                            completedGroups, donorTrees, originalAttachments, validationInputs);
                    int remaining = MAX_NAVIGATOR_BRANCH_ATTEMPTS - attempted;
                    List<RouteTreeOptimizer.TreeCandidate> continued = selectGroupContinuations(
                            completedGroups, joint, donorTrees, validationInputs);
                    int count = Math.min(continued.size(), remaining);
                    List<Integer> continuationBudgets = groupContinuationBudgets(
                            continued.subList(0, count), joint, remaining);
                    List<NavigatorGrowthSpec> jointSpecs = new ArrayList<>();
                    for (int index = 0; index < count; index++) {
                        RouteTreeOptimizer.TreeCandidate base = continued.get(index);
                        String trajectoryId = (joint.contains(base) ? "group:combined" : "group:continued")
                                + index;
                        notifyTreeTrajectory(listener, trajectoryId, base.getVariant());
                        int budget = continuationBudgets.get(index);
                        LOG.info("Group continuation trajectory={} base={} connected={} budget={}",
                                trajectoryId, base.getVariant().getId(),
                                representedTargets(base.getVariant()), budget);
                        jointSpecs.add(new NavigatorGrowthSpec(base, BranchChoicePolicy.GROUP_LOOKAHEAD,
                                trajectoryId, budget, true, Collections.emptyList()));
                        candidates.add(base);
                        if (!unsearchedReasonsByCandidate.containsKey(base.getVariant().getId())) {
                            Map<TypedId, String> reasons = new LinkedHashMap<>();
                            Set<TypedId> connected = representedTargets(base.getVariant());
                            for (FeatureIndex.IndexedFeature target : treeGrowthTargets) {
                                TypedId targetId = target.getFeature().getId();
                                if (!connected.contains(targetId)) reasons.put(targetId,
                                        initialUnsearchedReasons.getOrDefault(targetId,
                                                "GROUP_MERGE_NOT_EXPLORED"));
                            }
                            unsearchedReasonsByCandidate.put(base.getVariant().getId(), reasons);
                        }
                    }
                    List<NavigatorGrowthResult> jointResults = growNavigatorStage(jointSpecs,
                            treeGrowthTargets, validationInputs, searchArea, maximumVisibilityVertices,
                            remaining, searchExecutor, maximumParallelSearches, listener);
                    List<RouteTreeOptimizer.TreeCandidate> grownContinuations = new ArrayList<>();
                    for (int index = 0; index < jointResults.size(); index++) {
                        NavigatorGrowthResult scenario = jointResults.get(index);
                        BranchGrowth growth = scenario.growth;
                        RouteTreeOptimizer.TreeCandidate base = continued.get(index);
                        Map<TypedId, String> reasons = new LinkedHashMap<>(unsearchedReasonsByCandidate.get(
                                base.getVariant().getId()));
                        reasons.putAll(growth.unsearchedReasons);
                        unsearchedReasonsByCandidate.put(base.getVariant().getId(), reasons);
                        mergeNavigatorAttempts(attempts, scenario.attempts);
                        attempted += growth.attempted;
                        attached += growth.attached;
                        routeSearchIncomplete |= growth.searchIncomplete;
                        growth.rejected.forEach((reason, countRejected) ->
                                rejected.merge(reason, countRejected, Integer::sum));
                        if (growth.candidate != null) {
                            candidates.add(growth.candidate);
                            grownContinuations.add(growth.candidate);
                            grownSeeds++;
                            Set<TypedId> connected = representedTargets(growth.candidate.getVariant());
                            unsearchedReasonsByCandidate.put(growth.candidate.getVariant().getId(),
                                    reasonsForGrownCandidate(reasons, connected));
                        }
                    }
                    if (!grownContinuations.isEmpty()) {
                        Comparator<RouteTreeOptimizer.TreeCandidate> byCoverageAndCost = Comparator
                                .comparingInt((RouteTreeOptimizer.TreeCandidate candidate) ->
                                        connectedTargetCount(candidate.getVariant())).reversed()
                                .thenComparing(candidate -> groupConstruction(candidate, validationInputs))
                                .thenComparing(candidate -> candidate.getVariant().getId());
                        grownContinuations.sort(byCoverageAndCost);
                        RouteTreeOptimizer.TreeCandidate base = grownContinuations.get(0);
                        Set<TypedId> baseTargets = representedTargets(base.getVariant());
                        List<RouteTreeOptimizer.TreeCandidate> donors = new ArrayList<>(completedGroups);
                        donors.sort(Comparator
                                .comparingInt((RouteTreeOptimizer.TreeCandidate donor) -> {
                                    Set<TypedId> extra = representedTargets(donor.getVariant());
                                    extra.removeAll(baseTargets);
                                    return extra.size();
                                }).reversed()
                                .thenComparing(byCoverageAndCost));
                        for (RouteTreeOptimizer.TreeCandidate donor : donors) {
                            Set<TypedId> missing = representedTargets(donor.getVariant());
                            missing.removeAll(baseTargets);
                            if (missing.isEmpty()) continue;
                            TypedId targetId = missing.stream().min(TypedIdOrder.INSTANCE).get();
                            String graftId = "navigator:group:post-graft:" + targetId;
                            RouteTreeOptimizer.OptimizationResult graft = optimizer.graftDonorPrefix(
                                    graftId, base, donor, targetId, validationInputs);
                            Point destination = validationInputs.stream().filter(input ->
                                    targetId.equals(input.getFeature().getId())
                                            && input.getGeometry() instanceof Point)
                                    .map(input -> (Point) input.getGeometry()).findFirst().orElse(null);
                            RouteCandidate.Status status = graft.isFound()
                                    ? RouteCandidate.Status.TREE_BRANCH_ACCEPTED
                                    : RouteCandidate.Status.TREE_BRANCH_REJECTED;
                            attempts.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(status);
                            notifyTreeAttempt(listener, targetId, graftId, destination, status,
                                    graft.isFound() ? null : optimizationFailure(graft), null);
                            if (graft.isFound()) {
                                RouteTreeOptimizer.TreeCandidate acceptedGraft = graft.getCandidate();
                                candidates.add(acceptedGraft);
                                attached++;
                                unsearchedReasonsByCandidate.put(acceptedGraft.getVariant().getId(),
                                        reasonsForGrownCandidate(unsearchedReasonsByCandidate.getOrDefault(
                                                base.getVariant().getId(), Collections.emptyMap()),
                                                representedTargets(acceptedGraft.getVariant())));
                                notifyTreeTrajectory(listener, "group:post-graft", acceptedGraft.getVariant());
                            }
                            LOG.info("Group post-continuation graft base={} donor={} target={} accepted={} reason={}",
                                    base.getVariant().getId(), donor.getVariant().getId(), targetId,
                                    graft.isFound(), graft.isFound() ? "" : optimizationFailure(graft));
                            break;
                        }
                    }
                }
                LOG.info("Navigator tree growth rawSeeds={} distinctSeeds={} budgetedAttempts={} directAttempts={} attached={} grownSeeds={} budgetReached={} rejected={}",
                        rawSeeds.size(), seeds.size(), attempted, directAttempted, attached, grownSeeds,
                        attempted >= MAX_NAVIGATOR_BRANCH_ATTEMPTS, rejected);
            }
            candidates.sort(Comparator
                    .comparingInt((RouteTreeOptimizer.TreeCandidate candidate) ->
                            connectedTargetCount(candidate.getVariant())).reversed()
                    .thenComparingDouble(RouteTreeOptimizer.TreeCandidate::getTotalLengthM)
                    .thenComparing(candidate -> candidate.getVariant().getId()));
            if (!candidates.isEmpty()) {
                Set<TypedId> represented = representedTargets(candidates.get(0).getVariant());
                if (searchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR) {
                    Map<TypedId, String> selectedReasons = unsearchedReasonsByCandidate.getOrDefault(
                            candidates.get(0).getVariant().getId(), Collections.emptyMap());
                    unsearched.clear();
                    unrouted.clear();
                    for (TypedId targetId : targetIdsByIndex) {
                        if (represented.contains(targetId)) continue;
                        if (selectedReasons.containsKey(targetId)) unsearched.add(targetId);
                        else unrouted.add(targetId);
                    }
                }
                for (TypedId targetId : targetIdsByIndex) {
                    if (!represented.contains(targetId) && !unsearched.contains(targetId) && !unrouted.contains(targetId)) {
                        unrouted.add(targetId);
                        topologyOmission = true;
                    }
                }
            }
        } else {
            candidates = new ArrayList<>();
            List<RouteChoice> selected = new ArrayList<>();
            counter = new SearchCounter(maximumCombinations);
            combine(choices, 0, selected, validationInputs, candidates, counter);
            candidates.sort(Comparator.comparingDouble(RouteTreeOptimizer.TreeCandidate::getTotalLengthM));
        }

        MultiTargetRouteResult.Status status;
        String detail;
        if (!candidates.isEmpty()) {
            status = unrouted.isEmpty() && unsearched.isEmpty() && !routeSearchIncomplete
                    && !counter.limitReached && !topologyOmission
                    ? MultiTargetRouteResult.Status.COMPLETE : MultiTargetRouteResult.Status.PARTIAL;
            if (topologyOmission) {
                detail = "No complete compatible forest was found; the largest fully validated partial forest is returned";
            } else if (routeSearchIncomplete) {
                detail = "Valid routes were found, but one or more bounded searches were incomplete; alternatives may be missed";
            } else if (!unsearched.isEmpty()) {
                detail = "Some targets have no route candidate because their bounded search was incomplete";
            } else if (counter.limitReached) {
                detail = "Combination limit reached; returned candidates cover every target with a route";
            } else {
                detail = null;
            }
        } else if (counter.limitReached || routeSearchIncomplete || !unsearched.isEmpty()) {
            status = MultiTargetRouteResult.Status.SEARCH_LIMIT_REACHED;
            detail = counter.limitReached
                    ? "The bounded combination limit was reached before an acceptable complete tree was found"
                    : "The bounded route search stopped before an acceptable complete tree was found";
        } else {
            status = MultiTargetRouteResult.Status.NO_FEASIBLE_TREE;
            detail = "No tested combination passed topology, flow, diameter and engineering validation";
        }
        return new MultiTargetRouteResult(status, candidates, unrouted, unsearched, attempts,
                unsearchedReasonsByCandidate, counter.evaluated, counter.limitReached, detail);
    }

    static Map<TypedId, String> reasonsForGrownCandidate(Map<TypedId, String> baseReasons,
            Set<TypedId> connected) {
        Map<TypedId, String> grownReasons = new LinkedHashMap<>(baseReasons);
        grownReasons.keySet().removeAll(connected);
        return grownReasons;
    }

    private void combine(List<TargetChoices> choices, int index, List<RouteChoice> selected,
            List<FeatureIndex.IndexedFeature> validationInputs,
            List<RouteTreeOptimizer.TreeCandidate> results, SearchCounter counter) {
        if (index == choices.size()) {
            if (counter.evaluated >= counter.maximum) {
                counter.limitReached = true;
                return;
            }
            counter.evaluated++;
            List<Variant> routes = new ArrayList<>();
            List<AttachmentCandidate> attachments = new ArrayList<>();
            for (RouteChoice choice : selected) {
                routes.add(choice.variant);
                attachments.add(choice.attachment);
            }
            RouteTreeOptimizer.OptimizationResult optimized = optimizer.optimize(
                    "multi:" + counter.evaluated, routes, attachments, validationInputs);
            if (optimized.isFound()) results.add(optimized.getCandidate());
            return;
        }
        for (RouteChoice choice : choices.get(index).routes) {
            if (counter.limitReached) return;
            selected.add(choice);
            combine(choices, index + 1, selected, validationInputs, results, counter);
            selected.remove(selected.size() - 1);
        }
    }

    private AdaptiveCombinationResult combineAdaptive(List<TargetChoices> choices,
            List<FeatureIndex.IndexedFeature> validationInputs, int maximumCombinations) {
        List<TargetChoices> ordered = new ArrayList<>(choices);
        ordered.sort(Comparator.comparingInt((TargetChoices target) -> target.routes.size())
                .thenComparing(target -> target.targetId, ru.lct.heatnetwork.domain.TypedIdOrder.INSTANCE));
        int beamWidth = Math.max(1, Math.min(4, maximumCombinations / Math.max(1, ordered.size())));
        SearchCounter counter = new SearchCounter(maximumCombinations);
        List<ForestState> beam = new ArrayList<>();
        beam.add(new ForestState(Collections.emptyList(), null));

        // Primary pass: every target gets one composition attempt before alternatives consume the budget.
        for (TargetChoices target : ordered) {
            List<ForestState> next = new ArrayList<>(beam); // a failed addition keeps the accepted checkpoint
            for (ForestState state : beam) {
                if (counter.limitReached || target.routes.isEmpty()) break;
                ForestState accepted = optimizeAddition(state, target.routes.get(0),
                        validationInputs, counter);
                if (accepted != null) next.add(accepted);
            }
            beam = selectBeam(next, beamWidth);
        }

        // Retry targets omitted by an earlier conflict against each surviving forest.
        List<ForestState> retryStates = new ArrayList<>(beam);
        for (ForestState state : beam) {
            for (TargetChoices target : ordered) {
                if (counter.limitReached) break;
                if (choiceIndex(state, target.targetId) >= 0) continue;
                for (RouteChoice choice : target.routes) {
                    ForestState accepted = optimizeAddition(state, choice, validationInputs, counter);
                    if (accepted != null) {
                        retryStates.add(accepted);
                        break;
                    }
                    if (counter.limitReached) break;
                }
            }
        }
        beam = selectBeam(retryStates, beamWidth);

        // Spend the remaining budget on distinct attachment/corridor alternatives of accepted targets.
        List<ForestState> alternativeStates = new ArrayList<>(beam);
        for (ForestState state : beam) {
            for (TargetChoices target : ordered) {
                if (counter.limitReached) break;
                int selectedIndex = choiceIndex(state, target.targetId);
                if (selectedIndex < 0) continue;
                String selectedId = state.choices.get(selectedIndex).variant.getId();
                for (RouteChoice alternative : target.routes) {
                    if (selectedId.equals(alternative.variant.getId())) continue;
                    List<RouteChoice> replacement = new ArrayList<>(state.choices);
                    replacement.set(selectedIndex, alternative);
                    ForestState accepted = optimizeForest(replacement, validationInputs, counter);
                    if (accepted != null) alternativeStates.add(accepted);
                    if (counter.limitReached) break;
                }
            }
        }
        beam = selectBeam(alternativeStates, beamWidth);
        List<RouteTreeOptimizer.TreeCandidate> candidates = new ArrayList<>();
        for (ForestState state : beam) if (state.candidate != null) candidates.add(state.candidate);
        return new AdaptiveCombinationResult(candidates, counter);
    }

    private AdaptiveCombinationResult combineExhaustive(List<TargetChoices> choices,
            List<FeatureIndex.IndexedFeature> validationInputs, int maximumCombinations) {
        long startedNanos = System.nanoTime();
        SearchCounter counter = new SearchCounter(maximumCombinations);
        List<RouteTreeOptimizer.TreeCandidate> accepted = new ArrayList<>();
        Map<Integer, Integer> acceptedBySize = new TreeMap<>();
        Map<String, Integer> rejectedFiveOrMore = new TreeMap<>();
        enumerateFoundChoices(choices, 0, new ArrayList<>(), validationInputs, counter,
                accepted, acceptedBySize, rejectedFiveOrMore);
        LOG.info("Exhaustive found-route forest combinations={} limitReached={} acceptedBySize={} rejectedFiveOrMore={} elapsedMs={}",
                counter.evaluated, counter.limitReached, acceptedBySize, rejectedFiveOrMore,
                (System.nanoTime() - startedNanos) / 1_000_000L);
        return new AdaptiveCombinationResult(accepted, counter);
    }

    private BranchGrowth growDirectBranches(RouteTreeOptimizer.TreeCandidate initial,
            List<TargetChoices> choices, List<FeatureIndex.IndexedFeature> validationInputs, int budget) {
        RouteTreeOptimizer.TreeCandidate accepted = initial;
        List<TargetChoices> ordered = new ArrayList<>(choices);
        ordered.sort((first, second) -> TypedIdOrder.INSTANCE.compare(first.targetId, second.targetId));
        int attempted = 0;
        int attached = 0;
        Map<String, Integer> rejected = new TreeMap<>();
        for (TargetChoices target : ordered) {
            if (representedTargets(accepted.getVariant()).contains(target.targetId)) continue;
            if (!(target.feature.getGeometry() instanceof org.locationtech.jts.geom.Point)) continue;
            List<Variant.Segment> trunks = new ArrayList<>();
            for (Variant.Segment segment : accepted.getVariant().getSegments()) {
                if ("base".equals(segment.getLayingMethod()) && segment.getRestrictionIds().isEmpty()
                        && segment.getGeometry() != null && segment.getGeometry().getNumPoints() >= 2) {
                    trunks.add(segment);
                }
            }
            trunks.sort(Comparator
                    .comparingDouble((Variant.Segment segment) ->
                            segment.getGeometry().distance(target.feature.getGeometry()))
                    .thenComparing(Variant.Segment::getId));
            for (Variant.Segment trunk : trunks) {
                if (attempted >= budget) break;
                attempted++;
                RouteTreeOptimizer.OptimizationResult result = optimizer.attachDirectBranch(
                        "direct:" + attempted + ":" + accepted.getVariant().getId(), accepted,
                        target.feature, trunk.getId(), validationInputs);
                if (result.isFound()) {
                    accepted = result.getCandidate();
                    attached++;
                    break;
                }
                String reason = result.getValidation() != null && !result.getValidation().getIssues().isEmpty()
                        ? result.getValidation().getIssues().get(0).getCode() : result.getDetail();
                rejected.merge(reason == null ? "UNKNOWN" : reason, 1, Integer::sum);
            }
            if (attempted >= budget) break;
        }
        return new BranchGrowth(attached == 0 ? null : accepted, attempted, attached, rejected);
    }

    private BranchGrowth growNavigatorBranches(RouteTreeOptimizer.TreeCandidate initial,
            List<FeatureIndex.IndexedFeature> targets, List<FeatureIndex.IndexedFeature> validationInputs,
            Envelope searchArea, int maximumVisibilityVertices, int budget,
            Map<TypedId, List<RouteCandidate.Status>> attempts, RouteProgressListener listener,
            BranchChoicePolicy policy, String trajectoryId, boolean publishInitial) {
        RouteTreeOptimizer.TreeCandidate accepted = initial;
        ObstacleBufferCache routeBuffers = new ObstacleBufferCache();
        if (publishInitial) notifyTreeTrajectory(listener, trajectoryId, accepted.getVariant());
        String variantPrefix = policy == BranchChoicePolicy.FIRST_VALID
                && "first_valid:seed0".equals(trajectoryId) ? "navigator" : "navigator:" + trajectoryId;
        int attempted = 0, directAttempted = 0, attached = 0;
        boolean searchIncomplete = false;
        Map<String, Integer> rejected = new TreeMap<>();
        Map<TypedId, String> unsearchedReasons = new LinkedHashMap<>();
        Map<TypedId, Set<String>> attemptedGoals = new HashMap<>();
        Set<TypedId> firstPassVisited = new HashSet<>();
        Set<TypedId> actualAttempts = new HashSet<>();
        boolean fairFirstPass = policy == BranchChoicePolicy.SHORTEST_VALID;
        boolean changed;
        boolean pendingGoals;
        do {
            changed = false;
            pendingGoals = false;
            List<FeatureIndex.IndexedFeature> ordered = new ArrayList<>(targets);
            RouteTreeOptimizer.TreeCandidate current = accepted;
            ordered.removeIf(target -> representedTargets(current.getVariant())
                    .contains(target.getFeature().getId()) || !(target.getGeometry() instanceof Point));
            Map<TypedId, Point> rankingOrigins = new HashMap<>();
            ordered.sort(Comparator
                    .comparingDouble((FeatureIndex.IndexedFeature target) -> distanceToTree(
                            rankingOrigins.computeIfAbsent(target.getFeature().getId(), ignored ->
                                    singleTargetRouter.goalRankingOrigin(target, validationInputs, searchArea)),
                            current.getVariant()))
                    .thenComparing(target -> target.getFeature().getId(), TypedIdOrder.INSTANCE));
            boolean firstPassPending = fairFirstPass && ordered.stream().anyMatch(target ->
                    !firstPassVisited.contains(target.getFeature().getId()));
            for (FeatureIndex.IndexedFeature target : ordered) {
                if (attempted >= budget) break;
                TypedId targetId = target.getFeature().getId();
                if (firstPassPending && firstPassVisited.contains(targetId)) continue;
                if (!searchArea.covers(target.getGeometry().getCoordinate())) {
                    unsearchedReasons.put(targetId, "OUTSIDE_SEARCH_AREA");
                    firstPassVisited.add(targetId);
                    continue;
                }
                unsearchedReasons.putIfAbsent(targetId, "TREE_GOAL_WINDOW_LIMIT");
                List<RouteCandidate.Status> targetAttempts = attempts.computeIfAbsent(targetId,
                        ignored -> new ArrayList<>());
                List<Variant.Segment> trunks = eligibleTrunks(accepted.getVariant(), target,
                        rankingOrigins.computeIfAbsent(targetId, ignored ->
                                singleTargetRouter.goalRankingOrigin(target, validationInputs, searchArea)));
                boolean found = false;
                RouteTreeOptimizer.TreeCandidate bestDirect = null;
                Point bestDirectGoal = null;
                String bestDirectAttemptId = null;
                for (int trunkIndex = 0; !attemptedGoals.containsKey(targetId)
                        && trunkIndex < Math.min(firstPassPending ? 1 : 2, trunks.size()); trunkIndex++) {
                    if (attempted >= budget) break;
                    Variant.Segment trunk = trunks.get(trunkIndex);
                    String typedTargetKey = targetId.isNumeric() ? "number:" + targetId : "string:" + targetId;
                    String attemptId = "navigator:" + trajectoryId + ":direct:"
                            + typedTargetKey + ":" + trunkIndex;
                    LengthIndexedLine indexed = new LengthIndexedLine(trunk.getGeometry());
                    Point goal = trunk.getGeometry().getFactory().createPoint(indexed.extractPoint(
                            indexed.project(target.getGeometry().getCoordinate())));
                    goal.setSRID(32637);
                    RouteTreeOptimizer.OptimizationResult direct = optimizer.attachDirectBranch(
                            variantPrefix + ":direct:" + attached + ":" + accepted.getVariant().getId(),
                            accepted, target, trunk.getId(), validationInputs);
                    directAttempted++;
                    actualAttempts.add(targetId);
                    if (fairFirstPass) attempted++;
                    notifyTreeAttempt(listener, targetId, attemptId, goal,
                            direct.isFound() ? RouteCandidate.Status.FOUND
                                    : RouteCandidate.Status.TREE_BRANCH_REJECTED,
                            direct.isFound() ? null : optimizationFailure(direct),
                            direct.isFound() ? direct.getCandidate().getTotalLengthM()
                                    - accepted.getTotalLengthM() : null);
                    if (direct.isFound() && (bestDirect == null
                            || direct.getCandidate().getTotalLengthM() < bestDirect.getTotalLengthM())) {
                        bestDirect = direct.getCandidate();
                        bestDirectGoal = goal;
                        bestDirectAttemptId = attemptId;
                    }
                    if (direct.isFound() && policy == BranchChoicePolicy.FIRST_VALID) break;
                }
                if (bestDirect != null) {
                    double addedLength = bestDirect.getTotalLengthM() - accepted.getTotalLengthM();
                    accepted = bestDirect;
                    attached++;
                    changed = true;
                    found = true;
                    targetAttempts.add(RouteCandidate.Status.TREE_BRANCH_ACCEPTED);
                    notifyTreeAttempt(listener, targetId, bestDirectAttemptId, bestDirectGoal,
                            RouteCandidate.Status.TREE_BRANCH_ACCEPTED, null, addedLength);
                    notifyTreeTrajectory(listener, trajectoryId, accepted.getVariant());
                }
                if (found) {
                    firstPassVisited.add(targetId);
                    if (fairFirstPass && firstPassPending) continue;
                    break;
                }
                List<TreeGoal> goals = treeGoalsForSearch(accepted.getVariant(), target,
                        validationInputs, searchArea);
                Set<String> seenGoals = attemptedGoals.computeIfAbsent(targetId, ignored -> new HashSet<>());
                RouteTreeOptimizer.TreeCandidate bestRouted = null;
                LineString bestRoutedGeometry = null;
                Point bestRoutedGoal = null;
                String bestRoutedAttemptId = null;
                int visitLimit = firstPassPending ? 1 : MAX_NAVIGATOR_GOALS_PER_VISIT;
                int visitedThisPass = 0;
                for (TreeGoal goal : goals) {
                    if (attempted >= budget) break;
                    String goalKey = treeGoalKey(goal);
                    if (seenGoals.contains(goalKey)) continue;
                    if (visitedThisPass >= visitLimit) break;
                    seenGoals.add(goalKey);
                    visitedThisPass++;
                    attempted++;
                    actualAttempts.add(targetId);
                    String requestId = variantPrefix + ":search:" + attempted + ":"
                            + accepted.getVariant().getId();
                    TreeRouteRequest request = goal.chamberNodeId == null
                            ? new TreeRouteRequest(requestId, target, accepted, goal.trunkId,
                                    goal.point, validationInputs, searchArea, maximumVisibilityVertices)
                            : TreeRouteRequest.toChamber(requestId, target, accepted,
                                    goal.chamberNodeId, validationInputs, searchArea,
                                    maximumVisibilityVertices);
                    SingleTargetRouter.BranchRoute route = singleTargetRouter.routeToTree(request, routeBuffers);
                    if (route.getStatus() == RouteCandidate.Status.SEARCH_INCOMPLETE
                            || route.getStatus() == RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED
                            || route.getStatus() == RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH) {
                        searchIncomplete = true;
                        unsearchedReasons.put(targetId, route.getStatus().name());
                    }
                    if (!route.isFound()) {
                        notifyTreeAttempt(listener, targetId, request.getVariantId(), goal.point,
                                route.getStatus(), route.getDetail(), null);
                        rejected.merge(route.getStatus().name(), 1, Integer::sum);
                        if (route.getStatus() == RouteCandidate.Status.SEARCH_INCOMPLETE
                                || route.getStatus() == RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED) {
                            targetAttempts.add(RouteCandidate.Status.SEARCH_INCOMPLETE);
                        } else {
                            targetAttempts.add(RouteCandidate.Status.TREE_BRANCH_REJECTED);
                        }
                        continue;
                    }
                    String candidateId = variantPrefix + ":routed:" + attempted + ":"
                            + accepted.getVariant().getId();
                    RouteTreeOptimizer.OptimizationResult result = goal.chamberNodeId == null
                            ? optimizer.attachRoutedBranch(candidateId, accepted, target, goal.trunkId,
                                    route.getGeometry(), route.getDiameterMm(), validationInputs)
                            : optimizer.attachRoutedBranchAtChamber(candidateId, accepted, target,
                                    goal.chamberNodeId, route.getGeometry(), route.getDiameterMm(),
                                    validationInputs);
                    if (result.isFound()) {
                        notifyTreeAttempt(listener, targetId, request.getVariantId(), goal.point,
                                route.getStatus(), route.getDetail(), route.getGeometry().getLength());
                        if (bestRouted == null
                                || result.getCandidate().getTotalLengthM() < bestRouted.getTotalLengthM()) {
                            bestRouted = result.getCandidate();
                            bestRoutedGeometry = route.getGeometry();
                            bestRoutedGoal = goal.point;
                            bestRoutedAttemptId = request.getVariantId();
                        }
                        if (policy == BranchChoicePolicy.FIRST_VALID) break;
                        continue;
                    }
                    targetAttempts.add(RouteCandidate.Status.TREE_BRANCH_REJECTED);
                    String reason = optimizationFailure(result);
                    notifyTreeAttempt(listener, targetId, request.getVariantId(), goal.point,
                            RouteCandidate.Status.TREE_BRANCH_REJECTED, reason, null);
                    rejected.merge(reason == null ? "UNKNOWN" : reason, 1, Integer::sum);
                }
                if (goals.stream().anyMatch(goal -> !seenGoals.contains(treeGoalKey(goal)))) {
                    pendingGoals = true;
                }
                if (bestRouted != null) {
                    accepted = bestRouted;
                    attached++;
                    changed = true;
                    found = true;
                    targetAttempts.add(RouteCandidate.Status.TREE_BRANCH_ACCEPTED);
                    notifyTreeAttempt(listener, targetId, bestRoutedAttemptId, bestRoutedGoal,
                            RouteCandidate.Status.TREE_BRANCH_ACCEPTED, null,
                            bestRoutedGeometry.getLength());
                    notifyTreeTrajectory(listener, trajectoryId, accepted.getVariant());
                }
                firstPassVisited.add(targetId);
                if (found && !(fairFirstPass && firstPassPending)) break;
            }
            if (changed && !fairFirstPass) attemptedGoals.clear();
        } while ((changed || pendingGoals) && attempted < budget);
        Set<TypedId> connectedAtEnd = representedTargets(accepted.getVariant());
        boolean budgetExhaustedWithUnconnected = attempted >= budget && targets.stream().anyMatch(target ->
                !connectedAtEnd.contains(target.getFeature().getId()));
        for (FeatureIndex.IndexedFeature target : targets) {
            TypedId targetId = target.getFeature().getId();
            if (!connectedAtEnd.contains(targetId)) {
                if (!actualAttempts.contains(targetId) && searchArea.covers(target.getGeometry().getCoordinate())) {
                    unsearchedReasons.put(targetId, budgetExhaustedWithUnconnected
                            ? "NOT_ATTEMPTED_BUDGET" : "NO_TREE_GOAL_GENERATED");
                } else {
                    unsearchedReasons.putIfAbsent(targetId,
                            budgetExhaustedWithUnconnected ? "BRANCH_ATTEMPT_BUDGET" : "TREE_GOAL_WINDOW_LIMIT");
                }
            }
        }
        unsearchedReasons.keySet().removeIf(connectedAtEnd::contains);
        LOG.info("Navigator route buffers trajectory={} hits={} misses={} retained={}",
                trajectoryId, routeBuffers.hits(), routeBuffers.misses(), routeBuffers.size());
        routeBuffers.clear();
        return new BranchGrowth(attached == 0 ? null : accepted, attempted, directAttempted, attached,
                rejected, searchIncomplete || !unsearchedReasons.isEmpty(), unsearchedReasons);
    }

    /** Tries two new branches in a private tree and publishes only the validated complete pair. */
    private BranchGrowth growNavigatorGroup(RouteTreeOptimizer.TreeCandidate initial,
            List<FeatureIndex.IndexedFeature> targets, List<FeatureIndex.IndexedFeature> validationInputs,
            Envelope searchArea, int maximumVisibilityVertices, int budget,
            Map<TypedId, List<RouteCandidate.Status>> attempts, RouteProgressListener listener,
            String trajectoryId) {
        notifyTreeTrajectory(listener, trajectoryId, initial.getVariant());
        ObstacleBufferCache routeBuffers = new ObstacleBufferCache();
        RouteTreeOptimizer.TreeCandidate accepted = initial;
        Set<TypedId> checkedTargets = new HashSet<>();
        int checked = 0;
        int attached = 0;
        boolean incomplete = false;
        Map<String, Integer> rejected = new TreeMap<>();
        List<RouteTreeOptimizer.TreeCandidate> portfolioSeeds = new ArrayList<>();
        for (int step = 0; step < 8 && checked < budget; step++) {
            int stepLimit = Math.min(budget, checked + MAX_GROUP_ATTEMPTS_PER_STEP);
            List<GroupPair> pairs = groupPairs(accepted.getVariant(), targets,
                    validationInputs, searchArea);
            if (pairs.isEmpty()) break;
            Set<String> parentSegmentIds = new HashSet<>();
            for (Variant.Segment segment : accepted.getVariant().getSegments()) {
                parentSegmentIds.add(segment.getId());
            }
            RouteTreeOptimizer.TreeCandidate best = null;
            BigDecimal bestScore = null;
            RouteTreeOptimizer.TreeCandidate bestSingle = null;
            BigDecimal bestSingleScore = null;
            TypedId bestSingleTargetId = null;
            Map<TypedId, RouteTreeOptimizer.TreeCandidate> singleAlternatives = new LinkedHashMap<>();
            Map<Set<TypedId>, List<RouteTreeOptimizer.TreeCandidate>> pairAlternatives = new LinkedHashMap<>();
            Map<TypedId, Map<String, GroupAnchorTrial>> anchorTrials = new HashMap<>();
            for (int wave = 0; wave < 2 && checked < stepLimit; wave++) {
            for (GroupPair pair : pairs) {
            if (checked >= stepLimit) break;
            for (int order = 0; order < 2 && checked < stepLimit; order++) {
                FeatureIndex.IndexedFeature anchor = order == 0 ? pair.first : pair.second;
                FeatureIndex.IndexedFeature branch = order == 0 ? pair.second : pair.first;
                List<TreeGoal> anchorGoals = groupAnchorGoals(accepted.getVariant(),
                        anchor, branch, pair.trunkId, targets, validationInputs, searchArea);
                int anchorGoalIndex = 0;
                for (TreeGoal anchorGoal : anchorGoals) {
                    if (checked >= stepLimit) break;
                    int goalIndex = anchorGoalIndex++;
                    if (goalIndex < wave) continue;
                    if (goalIndex >= (wave == 0 ? 1 : MAX_GROUP_ANCHOR_GOALS)) break;
                    TypedId anchorId = anchor.getFeature().getId();
                    String goalKey = anchorGoal.trunkId + ":"
                            + Long.toHexString(Double.doubleToLongBits(anchorGoal.point.getX())) + ":"
                            + Long.toHexString(Double.doubleToLongBits(anchorGoal.point.getY()));
                    Map<String, GroupAnchorTrial> targetTrials = anchorTrials.computeIfAbsent(anchorId,
                            ignored -> new HashMap<>());
                    GroupAnchorTrial cached = targetTrials.get(goalKey);
                    String prefix;
                    RouteTreeOptimizer.TreeCandidate trial;
                    if (cached != null) {
                        if (cached.candidate == null) continue;
                        prefix = cached.prefix;
                        trial = cached.candidate;
                    } else {
                        checked++;
                        checkedTargets.add(anchorId);
                        prefix = "navigator:" + trajectoryId + ":" + checked;
                        TreeRouteRequest firstRequest = new TreeRouteRequest(prefix + ":anchor",
                                anchor, accepted, anchorGoal.trunkId, anchorGoal.point,
                                validationInputs, searchArea, maximumVisibilityVertices);
                        SingleTargetRouter.BranchRoute firstRoute = singleTargetRouter.routeToTree(firstRequest,
                                routeBuffers);
                        incomplete |= incomplete(firstRoute.getStatus());
                        recordGroupRouteAttempt(attempts, listener, anchor, firstRequest.getVariantId(),
                                anchorGoal.point, firstRoute, rejected);
                        if (!firstRoute.isFound()) {
                            targetTrials.put(goalKey, new GroupAnchorTrial(prefix, null));
                            continue;
                        }
                        RouteTreeOptimizer.OptimizationResult first = optimizer.attachRoutedBranch(
                                prefix + ":trial", accepted, anchor, anchorGoal.trunkId,
                                firstRoute.getGeometry(), firstRoute.getDiameterMm(), validationInputs);
                        if (!first.isFound()) {
                            String reason = optimizationFailure(first);
                            rejected.merge(reason == null ? "UNKNOWN" : reason, 1, Integer::sum);
                            notifyTreeAttempt(listener, anchorId, prefix + ":trial", anchorGoal.point,
                                    RouteCandidate.Status.TREE_BRANCH_REJECTED, reason, null);
                            targetTrials.put(goalKey, new GroupAnchorTrial(prefix, null));
                            continue;
                        }
                        trial = first.getCandidate();
                        targetTrials.put(goalKey, new GroupAnchorTrial(prefix, trial));
                    }
                    if (bestSingle == null || (anchorId.equals(bestSingleTargetId)
                            && groupScore(trial, validationInputs).compareTo(bestSingleScore) < 0)) {
                        bestSingle = trial;
                        bestSingleScore = groupScore(trial, validationInputs);
                        bestSingleTargetId = anchorId;
                    }
                    if (step == 0) {
                        RouteTreeOptimizer.TreeCandidate previous = singleAlternatives.get(anchorId);
                        if (previous == null || groupScore(trial, validationInputs)
                                .compareTo(groupScore(previous, validationInputs)) < 0) {
                            singleAlternatives.put(anchorId, trial);
                        }
                    }
                    int branchGoalCount = 0;
                    List<TreeGoal> branchGoals = new ArrayList<>(treeGoalsForSearch(trial.getVariant(), branch,
                            validationInputs, searchArea));
                    if (groupFocusedRouting) {
                        branchGoals.sort(Comparator.comparingInt((TreeGoal goal) ->
                                goal.trunkId != null && !parentSegmentIds.contains(goal.trunkId) ? 0 : 1)
                                .thenComparingDouble(goal -> goal.distance)
                                .thenComparing(goal -> goal.trunkId == null ? "" : goal.trunkId));
                    }
                    for (TreeGoal branchGoal : branchGoals) {
                        if (checked >= stepLimit || branchGoalCount >= (wave == 0
                                ? 1 : MAX_GROUP_BRANCH_GOALS)) break;
                        if (!groupFocusedRouting && (branchGoal.trunkId == null
                                || parentSegmentIds.contains(branchGoal.trunkId)
                                || !branchGoal.trunkId.contains(":branch:"))) continue;
                        if (branchGoal.trunkId == null && branchGoal.chamberNodeId == null) continue;
                        branchGoalCount++;
                        checked++;
                        checkedTargets.add(branch.getFeature().getId());
                        String secondId = prefix + ":branch:" + checked;
                        TreeRouteRequest secondRequest = branchGoal.chamberNodeId == null
                                ? new TreeRouteRequest(secondId, branch, trial, branchGoal.trunkId,
                                        branchGoal.point, validationInputs, searchArea, maximumVisibilityVertices)
                                : TreeRouteRequest.toChamber(secondId, branch, trial,
                                        branchGoal.chamberNodeId, validationInputs, searchArea,
                                        maximumVisibilityVertices);
                        SingleTargetRouter.BranchRoute secondRoute = singleTargetRouter.routeToTree(secondRequest,
                                routeBuffers);
                        incomplete |= incomplete(secondRoute.getStatus());
                        recordGroupRouteAttempt(attempts, listener, branch, secondId,
                                branchGoal.point, secondRoute, rejected);
                        if (!secondRoute.isFound()) continue;
                        RouteTreeOptimizer.OptimizationResult completed = branchGoal.chamberNodeId == null
                                ? optimizer.attachRoutedBranch(prefix + ":complete:" + checked, trial, branch,
                                        branchGoal.trunkId, secondRoute.getGeometry(),
                                        secondRoute.getDiameterMm(), validationInputs)
                                : optimizer.attachRoutedBranchAtChamber(prefix + ":complete:" + checked, trial,
                                        branch, branchGoal.chamberNodeId, secondRoute.getGeometry(),
                                        secondRoute.getDiameterMm(), validationInputs);
                        if (!completed.isFound()) {
                            String reason = optimizationFailure(completed);
                            rejected.merge(reason == null ? "UNKNOWN" : reason, 1, Integer::sum);
                            attempts.computeIfAbsent(branch.getFeature().getId(), ignored -> new ArrayList<>())
                                    .add(RouteCandidate.Status.TREE_BRANCH_REJECTED);
                            notifyTreeAttempt(listener, branch.getFeature().getId(), secondId + ":complete",
                                    branchGoal.point, RouteCandidate.Status.TREE_BRANCH_REJECTED, reason, null);
                            continue;
                        }
                        GroupContinuation continuation = continueGroup(accepted, completed.getCandidate(), targets,
                                validationInputs, searchArea, maximumVisibilityVertices,
                                Math.min(stepLimit - checked, MAX_GROUP_LOOKAHEAD_TARGETS
                                        * MAX_GROUP_LOOKAHEAD_GOALS_PER_TARGET), attempts, listener, prefix,
                                routeBuffers);
                        checked += continuation.attempted;
                        checkedTargets.addAll(continuation.checkedTargets);
                        incomplete |= continuation.incomplete;
                        continuation.rejected.forEach((reason, count) -> rejected.merge(reason, count, Integer::sum));
                        RouteTreeOptimizer.TreeCandidate evaluated = continuation.candidate == null
                                ? completed.getCandidate() : continuation.candidate;
                        BigDecimal score = groupScore(evaluated, validationInputs);
                        Set<TypedId> evaluatedTargets = representedTargets(evaluated.getVariant());
                        if (step == 0) {
                            retainPairAlternative(pairAlternatives, new HashSet<>(evaluatedTargets),
                                    evaluated, accepted.getVariant(), targets, validationInputs);
                        }
                        if (best == null || evaluatedTargets.size() > representedTargets(best.getVariant()).size()
                                || (evaluatedTargets.equals(representedTargets(best.getVariant()))
                                        && (score.compareTo(bestScore) < 0
                                                || (score.compareTo(bestScore) == 0
                                                        && evaluated.getTotalLengthM() < best.getTotalLengthM())))) {
                            best = evaluated;
                            bestScore = score;
                        }
                    }
                }
            }
        }
            }
            if (step == 0 && best != null) {
                Set<TypedId> bestCoverage = representedTargets(best.getVariant());
                RouteTreeOptimizer.TreeCandidate spatialAlternative = null;
                int greatestFutureOpportunity = -1;
                int greatestCoverage = -1;
                BigDecimal lowestAlternativeScore = null;
                for (List<RouteTreeOptimizer.TreeCandidate> sameCoverage : pairAlternatives.values()) {
                for (RouteTreeOptimizer.TreeCandidate alternative : sameCoverage) {
                    Set<TypedId> coverage = representedTargets(alternative.getVariant());
                    if (alternative == best || (coverage.equals(bestCoverage)
                            && DiscreteHausdorffDistance.distance(seedGeometry(alternative),
                                    seedGeometry(best)) < 4.0)) continue;
                    int futureOpportunity = futureOpportunityCount(accepted.getVariant(),
                            alternative.getVariant(), targets);
                    BigDecimal alternativeScore = groupScore(alternative, validationInputs);
                    if (spatialAlternative == null || futureOpportunity > greatestFutureOpportunity
                            || (futureOpportunity == greatestFutureOpportunity
                                    && (coverage.size() > greatestCoverage
                                            || (coverage.size() == greatestCoverage
                                                    && (alternativeScore.compareTo(lowestAlternativeScore) < 0
                                                            || (alternativeScore.compareTo(lowestAlternativeScore) == 0
                                                                    && alternative.getVariant().getId().compareTo(
                                                                            spatialAlternative.getVariant().getId()) < 0)))))) {
                        spatialAlternative = alternative;
                        greatestFutureOpportunity = futureOpportunity;
                        greatestCoverage = coverage.size();
                        lowestAlternativeScore = alternativeScore;
                    }
                }
                }
                if (spatialAlternative != null) portfolioSeeds.add(spatialAlternative);
                for (RouteTreeOptimizer.TreeCandidate alternative : singleAlternatives.values()) {
                    if (portfolioSeeds.size() >= MAX_PORTFOLIO_SEEDS) break;
                    portfolioSeeds.add(alternative);
                }
            }
            boolean acceptedSingle = best == null && bestSingle != null;
            if (acceptedSingle) {
                best = bestSingle;
                bestScore = bestSingleScore;
            }
            if (best == null) break;
            Set<TypedId> added = representedTargets(best.getVariant());
            added.removeAll(representedTargets(accepted.getVariant()));
            if (added.size() < (acceptedSingle ? 1 : 2) || added.size() > 3) break;
            for (TypedId targetId : added) {
                attempts.computeIfAbsent(targetId, ignored -> new ArrayList<>())
                        .add(RouteCandidate.Status.TREE_BRANCH_ACCEPTED);
            }
            attached += added.size();
            BigDecimal parentConstruction = groupConstruction(accepted, validationInputs);
            BigDecimal resultConstruction = groupConstruction(best, validationInputs);
            BigDecimal deltaConstruction = resultConstruction.subtract(parentConstruction);
            double deltaLength = best.getTotalLengthM() - accepted.getTotalLengthM();
            LOG.info("Navigator group accepted trajectory={} connected={} deltaConstructionRub={} deltaUniqueLengthM={} score={}",
                    trajectoryId, added, deltaConstruction, deltaLength, bestScore);
            String explanation = (acceptedSingle ? "Проверенный отвод: прирост стоимости "
                    : "Общая сеть: прирост стоимости ") + deltaConstruction
                    + " ₽, уникальной длины " + Math.round(deltaLength * 100.0) / 100.0
                    + " м; score " + bestScore;
            for (FeatureIndex.IndexedFeature target : targets) {
                if (added.contains(target.getFeature().getId())) {
                    notifyTreeAttempt(listener, target.getFeature().getId(),
                            "navigator:" + trajectoryId + ":accepted:" + (step + 1),
                            (Point) target.getGeometry(), RouteCandidate.Status.TREE_BRANCH_ACCEPTED,
                            explanation, deltaLength);
                }
            }
            accepted = best;
            notifyTreeTrajectory(listener, trajectoryId, accepted.getVariant());
        }
        Map<TypedId, String> fallbackReasons = Collections.emptyMap();
        Set<TypedId> representedBeforeFallback = representedTargets(accepted.getVariant());
        if (checked < budget && targets.stream().anyMatch(target ->
                !representedBeforeFallback.contains(target.getFeature().getId()))) {
            BranchGrowth fallback = growNavigatorBranches(accepted, targets, validationInputs,
                    searchArea, maximumVisibilityVertices, budget - checked, attempts, listener,
                    BranchChoicePolicy.SHORTEST_VALID, trajectoryId, false);
            checked += fallback.attempted;
            attached += fallback.attached;
            incomplete |= fallback.searchIncomplete;
            fallback.rejected.forEach((reason, count) -> rejected.merge(reason, count, Integer::sum));
            fallbackReasons = fallback.unsearchedReasons;
            if (fallback.candidate != null) accepted = fallback.candidate;
        }
        Map<TypedId, String> unsearchedReasons = new LinkedHashMap<>();
        Set<TypedId> connected = representedTargets(accepted.getVariant());
        for (FeatureIndex.IndexedFeature target : targets) {
            TypedId targetId = target.getFeature().getId();
            if (connected.contains(targetId)) continue;
            if (!searchArea.covers(target.getGeometry().getCoordinate())) {
                unsearchedReasons.put(targetId, "OUTSIDE_SEARCH_AREA");
            } else if (fallbackReasons.containsKey(targetId)) {
                unsearchedReasons.put(targetId, fallbackReasons.get(targetId));
            } else if (!checkedTargets.contains(targetId)) {
                unsearchedReasons.put(targetId, checked >= budget
                        ? "NOT_ATTEMPTED_GROUP_BUDGET" : "NOT_ATTEMPTED_GROUP_SCOPE");
            } else {
                unsearchedReasons.put(targetId, "GROUP_CANDIDATES_REJECTED_OR_INCOMPLETE");
            }
        }
        LOG.info("Navigator group route buffers trajectory={} hits={} misses={} retained={}",
                trajectoryId, routeBuffers.hits(), routeBuffers.misses(), routeBuffers.size());
        routeBuffers.clear();
        return new BranchGrowth(attached == 0 ? null : accepted, checked, 0, attached, rejected,
                incomplete || !unsearchedReasons.isEmpty(), unsearchedReasons, portfolioSeeds);
    }

    /** Tries one more real target against the entire validated trial tree, including its new branches. */
    private GroupContinuation continueGroup(RouteTreeOptimizer.TreeCandidate parent,
            RouteTreeOptimizer.TreeCandidate pair,
            List<FeatureIndex.IndexedFeature> targets, List<FeatureIndex.IndexedFeature> validationInputs,
            Envelope searchArea, int maximumVisibilityVertices, int budget,
            Map<TypedId, List<RouteCandidate.Status>> attempts, RouteProgressListener listener,
            String prefix, ObstacleBufferCache routeBuffers) {
        Set<TypedId> connected = representedTargets(pair.getVariant());
        Set<String> parentSegmentIds = new HashSet<>();
        for (Variant.Segment segment : parent.getVariant().getSegments()) {
            parentSegmentIds.add(segment.getId());
        }
        List<Variant.Segment> newSegments = new ArrayList<>();
        for (Variant.Segment segment : pair.getVariant().getSegments()) {
            if (!parentSegmentIds.contains(segment.getId()) && parent.getVariant().getSegments().stream()
                    .noneMatch(previous -> previous.getGeometry().covers(segment.getGeometry()))) {
                newSegments.add(segment);
            }
        }
        Set<String> newSegmentIds = new HashSet<>();
        for (Variant.Segment segment : newSegments) newSegmentIds.add(segment.getId());
        List<FeatureIndex.IndexedFeature> remaining = selectLookaheadTargets(parent.getVariant(),
                pair.getVariant(), targets, searchArea);
        int attempted = 0;
        boolean incomplete = false;
        Map<String, Integer> rejected = new TreeMap<>();
        Set<TypedId> checkedTargets = new HashSet<>();
        RouteTreeOptimizer.TreeCandidate best = null;
        BigDecimal bestScore = null;
        for (FeatureIndex.IndexedFeature target : remaining) {
            int goalCount = 0;
            List<TreeGoal> goals = new ArrayList<>(treeGoalsForSearch(pair.getVariant(), target,
                    validationInputs, searchArea));
            goals.sort(Comparator.comparingInt((TreeGoal goal) ->
                    newSegmentIds.contains(goal.trunkId) ? 0 : 1)
                    .thenComparingDouble(goal -> goal.distance)
                    .thenComparing(goal -> goal.trunkId == null ? "" : goal.trunkId));
            for (TreeGoal goal : goals) {
                if (attempted >= budget || goalCount >= MAX_GROUP_LOOKAHEAD_GOALS_PER_TARGET) break;
                attempted++;
                goalCount++;
                checkedTargets.add(target.getFeature().getId());
                String requestId = prefix + ":lookahead:" + attempted;
                TreeRouteRequest request = goal.chamberNodeId == null
                        ? new TreeRouteRequest(requestId, target, pair, goal.trunkId, goal.point,
                                validationInputs, searchArea, maximumVisibilityVertices)
                        : TreeRouteRequest.toChamber(requestId, target, pair, goal.chamberNodeId,
                                validationInputs, searchArea, maximumVisibilityVertices);
                SingleTargetRouter.BranchRoute route = singleTargetRouter.routeToTree(request, routeBuffers);
                incomplete |= incomplete(route.getStatus());
                recordGroupRouteAttempt(attempts, listener, target, requestId, goal.point, route, rejected);
                if (!route.isFound()) continue;
                RouteTreeOptimizer.OptimizationResult result = goal.chamberNodeId == null
                        ? optimizer.attachRoutedBranch(requestId + ":complete", pair, target, goal.trunkId,
                                route.getGeometry(), route.getDiameterMm(), validationInputs)
                        : optimizer.attachRoutedBranchAtChamber(requestId + ":complete", pair, target,
                                goal.chamberNodeId, route.getGeometry(), route.getDiameterMm(), validationInputs);
                if (!result.isFound()) {
                    String reason = optimizationFailure(result);
                    rejected.merge(reason == null ? "UNKNOWN" : reason, 1, Integer::sum);
                    attempts.computeIfAbsent(target.getFeature().getId(), ignored -> new ArrayList<>())
                            .add(RouteCandidate.Status.TREE_BRANCH_REJECTED);
                    notifyTreeAttempt(listener, target.getFeature().getId(), requestId + ":complete",
                            goal.point, RouteCandidate.Status.TREE_BRANCH_REJECTED, reason, null);
                    continue;
                }
                BigDecimal score = groupScore(result.getCandidate(), validationInputs);
                Set<TypedId> resultTargets = representedTargets(result.getCandidate().getVariant());
                if (best == null || (resultTargets.equals(representedTargets(best.getVariant()))
                        && score.compareTo(bestScore) < 0)) {
                    best = result.getCandidate();
                    bestScore = score;
                }
            }
        }
        return new GroupContinuation(best, attempted, incomplete, rejected, checkedTargets);
    }

    /** Spend one bounded lookahead slot near the new pair and one in another direction. */
    static List<FeatureIndex.IndexedFeature> selectLookaheadTargets(Variant parent, Variant pair,
            List<FeatureIndex.IndexedFeature> targets, Envelope searchArea) {
        Set<TypedId> connected = representedTargets(pair);
        List<FeatureIndex.IndexedFeature> remaining = new ArrayList<>();
        for (FeatureIndex.IndexedFeature target : targets) {
            if (!connected.contains(target.getFeature().getId()) && target.getGeometry() instanceof Point
                    && searchArea.covers(target.getGeometry().getCoordinate())) {
                remaining.add(target);
            }
        }
        if (remaining.isEmpty()) return remaining;
        Comparator<FeatureIndex.IndexedFeature> byPairDistance = Comparator
                .comparingDouble((FeatureIndex.IndexedFeature target) -> distanceToTree(target, pair))
                .thenComparing(target -> target.getFeature().getId(), TypedIdOrder.INSTANCE);
        FeatureIndex.IndexedFeature near = remaining.stream()
                .filter(target -> distanceToTree(target, pair) + 1e-6 < distanceToTree(target, parent))
                .min(byPairDistance).orElseGet(() -> remaining.stream().min(byPairDistance).get());
        List<FeatureIndex.IndexedFeature> selected = new ArrayList<>();
        selected.add(near);
        if (MAX_GROUP_LOOKAHEAD_TARGETS > 1 && remaining.size() > 1) {
            FeatureIndex.IndexedFeature far = remaining.stream()
                    .filter(target -> !target.getFeature().getId().equals(near.getFeature().getId()))
                    .max(Comparator
                            .comparingDouble((FeatureIndex.IndexedFeature target) ->
                                    target.getGeometry().distance(near.getGeometry()))
                            .thenComparingDouble(target -> distanceToTree(target, parent)
                                    - distanceToTree(target, pair))
                            .thenComparing(target -> target.getFeature().getId(), TypedIdOrder.INSTANCE))
                    .get();
            selected.add(far);
        }
        return selected;
    }

    private static BigDecimal groupConstruction(RouteTreeOptimizer.TreeCandidate candidate,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        Set<TypedId> connected = representedTargets(candidate.getVariant());
        List<TypedId> unsearched = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : validationInputs) {
            if ("oks_connection_point".equals(input.getFeature().getObjectType())
                    && !connected.contains(input.getFeature().getId())) {
                unsearched.add(input.getFeature().getId());
            }
        }
        return new VariantCostCalculator().calculate(candidate.getVariant(), candidate.getValidation(),
                validationInputs, Collections.emptyList(), unsearched).getSummary().getConstructionCost();
    }

    private List<RouteTreeOptimizer.TreeCandidate> combineGroupAlternatives(
            List<RouteTreeOptimizer.TreeCandidate> groups,
            List<RouteTreeOptimizer.TreeCandidate> donorTrees,
            List<AttachmentCandidate> originalAttachments,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        Comparator<RouteTreeOptimizer.TreeCandidate> byCoverageAndCost = Comparator
                .comparingInt((RouteTreeOptimizer.TreeCandidate candidate) ->
                        connectedTargetCount(candidate.getVariant())).reversed()
                .thenComparing(candidate -> groupConstruction(candidate, validationInputs))
                .thenComparing(candidate -> candidate.getVariant().getId());
        List<RouteTreeOptimizer.TreeCandidate> bases = new ArrayList<>(groups);
        bases.sort(byCoverageAndCost);
        List<RouteTreeOptimizer.TreeCandidate> donors = new ArrayList<>(donorTrees);
        donors.addAll(groups);
        List<RouteTreeOptimizer.TreeCandidate> accepted = new ArrayList<>();
        int checked = 0;
        int graftAttempts = 0;
        for (RouteTreeOptimizer.TreeCandidate base : bases) {
            if (checked >= 12) break;
            Set<TypedId> baseTargets = representedTargets(base.getVariant());
            donors.sort(Comparator
                    .comparingInt((RouteTreeOptimizer.TreeCandidate donor) -> {
                        Set<TypedId> extra = representedTargets(donor.getVariant());
                        extra.removeAll(baseTargets);
                        return extra.size();
                    }).reversed()
                    .thenComparing(byCoverageAndCost));
            for (RouteTreeOptimizer.TreeCandidate donor : donors) {
                if (checked >= 12) break;
                Set<TypedId> added = representedTargets(donor.getVariant());
                added.removeAll(baseTargets);
                if (added.isEmpty()) continue;
                String id = "navigator:group:combined:" + checked++;
                RouteTreeOptimizer.OptimizationResult merged = optimizer.combineValidatedTrees(id,
                        base, donor, originalAttachments, validationInputs);
                if (merged.isFound()) {
                    accepted.add(merged.getCandidate());
                    LOG.info("Group forests merged base={} donor={} added={} connected={} costRub={}",
                            base.getVariant().getId(), donor.getVariant().getId(), added,
                            connectedTargetCount(merged.getCandidate().getVariant()),
                            groupConstruction(merged.getCandidate(), validationInputs));
                } else {
                    LOG.info("Group forests rejected base={} donor={} added={} reason={}",
                            base.getVariant().getId(), donor.getVariant().getId(), added,
                            optimizationFailure(merged));
                    RouteTreeOptimizer.TreeCandidate grafted = base;
                    List<TypedId> missing = new ArrayList<>(added);
                    missing.sort(TypedIdOrder.INSTANCE);
                    for (TypedId targetId : missing) {
                        if (graftAttempts >= 23) break;
                        String graftId = id + ":graft:" + graftAttempts++;
                        RouteTreeOptimizer.OptimizationResult graft = optimizer.graftDonorPrefix(
                                graftId, grafted, donor, targetId, validationInputs);
                        if (graft.isFound()) {
                            grafted = graft.getCandidate();
                            accepted.add(grafted);
                            LOG.info("Group donor prefix grafted base={} donor={} target={} connected={} costRub={}",
                                    base.getVariant().getId(), donor.getVariant().getId(), targetId,
                                    connectedTargetCount(grafted.getVariant()),
                                    groupConstruction(grafted, validationInputs));
                        } else {
                            LOG.info("Group donor prefix rejected base={} donor={} target={} reason={}",
                                    base.getVariant().getId(), donor.getVariant().getId(), targetId,
                                    optimizationFailure(graft));
                        }
                    }
                }
            }
        }
        accepted.sort(byCoverageAndCost);
        LOG.info("Group forest alternatives attempted={} graftAttempts={} accepted={}",
                checked, graftAttempts, accepted.size());
        return accepted;
    }

    /** Keeps both a strong trunk and the independently validated graft with future growth potential. */
    static List<RouteTreeOptimizer.TreeCandidate> selectGroupContinuations(
            List<RouteTreeOptimizer.TreeCandidate> groups,
            List<RouteTreeOptimizer.TreeCandidate> joint,
            List<RouteTreeOptimizer.TreeCandidate> donors,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        if (groups.isEmpty()) return Collections.emptyList();
        Comparator<RouteTreeOptimizer.TreeCandidate> byCoverageAndCost = Comparator
                .comparingInt((RouteTreeOptimizer.TreeCandidate candidate) ->
                        connectedTargetCount(candidate.getVariant())).reversed()
                .thenComparing(candidate -> groupConstruction(candidate, validationInputs))
                .thenComparing(candidate -> candidate.getVariant().getId());
        List<RouteTreeOptimizer.TreeCandidate> rankedGroups = new ArrayList<>(groups);
        rankedGroups.sort(byCoverageAndCost);
        List<RouteTreeOptimizer.TreeCandidate> selected = new ArrayList<>();
        selected.add(rankedGroups.get(0));
        Set<TypedId> covered = representedTargets(rankedGroups.get(0).getVariant());
        List<RouteTreeOptimizer.TreeCandidate> rankedJoint = new ArrayList<>(joint);
        rankedJoint.sort(byCoverageAndCost);
        for (RouteTreeOptimizer.TreeCandidate candidate : rankedJoint) {
            if (sameTargetsAndGeometry(selected.get(0), candidate)) continue;
            selected.add(candidate);
            covered.addAll(representedTargets(candidate.getVariant()));
            break;
        }
        List<RouteTreeOptimizer.TreeCandidate> pool = new ArrayList<>(joint);
        pool.addAll(groups);
        pool.addAll(donors);
        while (selected.size() < MAX_GROUP_CONTINUATIONS) {
            List<RouteTreeOptimizer.TreeCandidate> available = new ArrayList<>();
            for (RouteTreeOptimizer.TreeCandidate candidate : pool) {
                if (selected.contains(candidate)) continue;
                boolean duplicate = selected.stream().anyMatch(previous ->
                        sameTargetsAndGeometry(previous, candidate));
                if (!duplicate) available.add(candidate);
            }
            if (available.isEmpty()) break;
            available.sort(Comparator
                    .comparingInt((RouteTreeOptimizer.TreeCandidate candidate) -> {
                        Set<TypedId> extra = representedTargets(candidate.getVariant());
                        extra.removeAll(covered);
                        return extra.size();
                    }).reversed()
                    .thenComparing(byCoverageAndCost));
            RouteTreeOptimizer.TreeCandidate next = available.get(0);
            Set<TypedId> extra = representedTargets(next.getVariant());
            extra.removeAll(covered);
            if (extra.isEmpty()) {
                next = rankedGroups.stream().filter(available::contains).findFirst().orElse(null);
                if (next == null) break;
            }
            selected.add(next);
            covered.addAll(representedTargets(next.getVariant()));
        }
        return selected;
    }

    private static boolean sameTargetsAndGeometry(RouteTreeOptimizer.TreeCandidate left,
            RouteTreeOptimizer.TreeCandidate right) {
        return representedTargets(left.getVariant()).equals(representedTargets(right.getVariant()))
                && DiscreteHausdorffDistance.distance(seedGeometry(left), seedGeometry(right)) < 1.0;
    }

    /** Reserves the former two-wave depth for one validated graft without increasing the total budget. */
    static List<Integer> groupContinuationBudgets(List<RouteTreeOptimizer.TreeCandidate> continued,
            List<RouteTreeOptimizer.TreeCandidate> joint, int remaining) {
        int count = continued.size();
        if (count == 0) return Collections.emptyList();
        if (remaining < count) throw new IllegalArgumentException("Each continuation needs one attempt");
        int priority = -1;
        for (int index = 0; index < count; index++) {
            if (joint.contains(continued.get(index))) {
                priority = index;
                break;
            }
        }
        int reserved = priority < 0 ? 0 : count == 1 ? remaining
                : Math.min(MAX_GROUP_CONTROL_ATTEMPTS, remaining - count + 1);
        int rest = remaining - reserved;
        int otherCount = count - (priority < 0 ? 0 : 1);
        List<Integer> budgets = new ArrayList<>(count);
        int assigned = 0;
        for (int index = 0; index < count; index++) {
            int budget;
            if (index == priority) budget = reserved;
            else {
                int otherIndex = index < priority || priority < 0 ? index : index - 1;
                budget = rest / otherCount + (otherIndex < rest % otherCount ? 1 : 0);
            }
            budgets.add(budget);
            assigned += budget;
        }
        if (assigned != remaining) throw new IllegalStateException("Continuation budget mismatch");
        return budgets;
    }

    /** Adds a rootward anchor for a nearby third goal before fixing the first pipe. */
    private List<TreeGoal> groupAnchorGoals(Variant tree,
            FeatureIndex.IndexedFeature anchor, FeatureIndex.IndexedFeature partner,
            String trunkId, List<FeatureIndex.IndexedFeature> targets,
            List<FeatureIndex.IndexedFeature> validationInputs, Envelope searchArea) {
        List<TreeGoal> baseline = new ArrayList<>();
        for (TreeGoal goal : treeGoalsForSearch(tree, anchor, validationInputs, searchArea)) {
            if (trunkId.equals(goal.trunkId)) baseline.add(goal);
        }
        if (baseline.isEmpty()) return baseline;
        List<TreeGoal> result = new ArrayList<>();
        result.add(baseline.get(0));
        Variant.Segment trunk = tree.getSegments().stream()
                .filter(segment -> trunkId.equals(segment.getId())).findFirst().orElse(null);
        if (trunk != null) {
            FeatureIndex.IndexedFeature third = targets.stream()
                    .filter(target -> target.getGeometry() instanceof Point
                            && !target.getFeature().getId().equals(anchor.getFeature().getId())
                            && !target.getFeature().getId().equals(partner.getFeature().getId())
                            && !representedTargets(tree).contains(target.getFeature().getId()))
                    .min(Comparator.comparingDouble((FeatureIndex.IndexedFeature target) ->
                            target.getGeometry().distance(partner.getGeometry()))
                            .thenComparing(target -> target.getFeature().getId(), TypedIdOrder.INSTANCE))
                    .orElse(null);
            if (third != null) {
                LineString geometry = trunk.getGeometry();
                LengthIndexedLine indexed = new LengthIndexedLine(geometry);
                double measure = indexed.project(third.getGeometry().getCoordinate()) + 5.0;
                if (measure > 0.25 && measure < geometry.getLength() - 0.25) {
                    Point point = geometry.getFactory().createPoint(new Coordinate(indexed.extractPoint(measure)));
                    point.setSRID(32637);
                    if (baseline.get(0).point.distance(point) >= 1.0
                            && java.util.Arrays.stream(geometry.getCoordinates())
                                    .noneMatch(vertex -> vertex.distance(point.getCoordinate()) < 0.25)) {
                        TreeGoal additional = TreeGoal.trunk(trunkId, point,
                                anchor.getGeometry().distance(point));
                        if (candidateGoalIsClear(tree, anchor, validationInputs, additional)) {
                            result.add(additional);
                        }
                    }
                }
            }
        }
        if (result.size() == 1 && baseline.size() > 1) result.add(baseline.get(1));
        return result;
    }

    private static final class GroupContinuation {
        private final RouteTreeOptimizer.TreeCandidate candidate;
        private final int attempted;
        private final boolean incomplete;
        private final Map<String, Integer> rejected;
        private final Set<TypedId> checkedTargets;
        private GroupContinuation(RouteTreeOptimizer.TreeCandidate candidate, int attempted,
                boolean incomplete, Map<String, Integer> rejected, Set<TypedId> checkedTargets) {
            this.candidate = candidate;
            this.attempted = attempted;
            this.incomplete = incomplete;
            this.rejected = rejected;
            this.checkedTargets = checkedTargets;
        }
    }

    private static final class GroupAnchorTrial {
        private final String prefix;
        private final RouteTreeOptimizer.TreeCandidate candidate;

        private GroupAnchorTrial(String prefix, RouteTreeOptimizer.TreeCandidate candidate) {
            this.prefix = prefix;
            this.candidate = candidate;
        }
    }

    private static void recordGroupRouteAttempt(Map<TypedId, List<RouteCandidate.Status>> attempts,
            RouteProgressListener listener, FeatureIndex.IndexedFeature target, String attemptId,
            Point goal, SingleTargetRouter.BranchRoute route, Map<String, Integer> rejected) {
        TypedId targetId = target.getFeature().getId();
        attempts.computeIfAbsent(targetId, ignored -> new ArrayList<>()).add(route.getStatus());
        notifyTreeAttempt(listener, targetId, attemptId, goal, route.getStatus(),
                route.getDetail(), route.isFound() ? route.getGeometry().getLength() : null);
        if (!route.isFound()) rejected.merge(route.getStatus().name(), 1, Integer::sum);
    }

    private static boolean incomplete(RouteCandidate.Status status) {
        return status == RouteCandidate.Status.SEARCH_INCOMPLETE
                || status == RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED
                || status == RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH;
    }

    private static BigDecimal groupScore(RouteTreeOptimizer.TreeCandidate candidate,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        Set<TypedId> connected = representedTargets(candidate.getVariant());
        List<TypedId> unsearched = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : validationInputs) {
            if ("oks_connection_point".equals(input.getFeature().getObjectType())
                    && !connected.contains(input.getFeature().getId())) {
                unsearched.add(input.getFeature().getId());
            }
        }
        return new VariantCostCalculator().calculate(candidate.getVariant(), candidate.getValidation(),
                validationInputs, Collections.emptyList(), unsearched).getSummary().getScore();
    }

    /** Preserve a cheap and a promising geometry for each validated target set. */
    private static void retainPairAlternative(
            Map<Set<TypedId>, List<RouteTreeOptimizer.TreeCandidate>> candidates,
            Set<TypedId> coverage, RouteTreeOptimizer.TreeCandidate candidate, Variant parent,
            List<FeatureIndex.IndexedFeature> targets,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        List<RouteTreeOptimizer.TreeCandidate> sameCoverage = candidates.computeIfAbsent(coverage,
                ignored -> new ArrayList<>());
        Geometry shape = seedGeometry(candidate);
        BigDecimal score = groupScore(candidate, validationInputs);
        for (int i = 0; i < sameCoverage.size(); i++) {
            RouteTreeOptimizer.TreeCandidate previous = sameCoverage.get(i);
            if (DiscreteHausdorffDistance.distance(shape, seedGeometry(previous)) < 4.0) {
                BigDecimal previousScore = groupScore(previous, validationInputs);
                if (score.compareTo(previousScore) < 0 || (score.compareTo(previousScore) == 0
                        && candidate.getVariant().getId().compareTo(previous.getVariant().getId()) < 0)) {
                    sameCoverage.set(i, candidate);
                }
                return;
            }
        }
        sameCoverage.add(candidate);
        sameCoverage.sort(Comparator
                .comparingInt((RouteTreeOptimizer.TreeCandidate alternative) ->
                        futureOpportunityCount(parent, alternative.getVariant(), targets)).reversed()
                .thenComparing(alternative -> groupScore(alternative, validationInputs))
                .thenComparing(alternative -> alternative.getVariant().getId()));
        if (sameCoverage.size() > 2) sameCoverage.remove(2);
    }

    /** Keep regional coverage first, then genuinely different first-pipe geometries for lookahead. */
    private static List<RouteTreeOptimizer.TreeCandidate> diverseGroupSeeds(
            List<RouteTreeOptimizer.TreeCandidate> ranked) {
        List<RouteTreeOptimizer.TreeCandidate> selected = new ArrayList<>();
        Set<Set<TypedId>> represented = new HashSet<>();
        for (RouteTreeOptimizer.TreeCandidate candidate : ranked) {
            if (represented.add(representedTargets(candidate.getVariant()))) selected.add(candidate);
            if (selected.size() >= Math.min(MAX_INITIAL_SEED_TARGETS, MAX_GROUP_SEEDS)) break;
        }
        Map<RouteTreeOptimizer.TreeCandidate, Geometry> shapes = new HashMap<>();
        for (RouteTreeOptimizer.TreeCandidate candidate : ranked) {
            if (selected.size() >= MAX_GROUP_SEEDS) break;
            Set<TypedId> coverage = representedTargets(candidate.getVariant());
            List<RouteTreeOptimizer.TreeCandidate> sameTarget = new ArrayList<>();
            for (RouteTreeOptimizer.TreeCandidate previous : selected) {
                if (representedTargets(previous.getVariant()).equals(coverage)) sameTarget.add(previous);
            }
            if (sameTarget.isEmpty() || sameTarget.size() >= 2 || sameTarget.contains(candidate)) continue;
            Geometry shape = shapes.computeIfAbsent(candidate, MultiTargetRouter::seedGeometry);
            if (sameTarget.stream().allMatch(previous -> DiscreteHausdorffDistance.distance(shape,
                    shapes.computeIfAbsent(previous, MultiTargetRouter::seedGeometry)) >= 4.0)) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    private static Geometry seedGeometry(RouteTreeOptimizer.TreeCandidate candidate) {
        List<Geometry> segments = new ArrayList<>();
        for (Variant.Segment segment : candidate.getVariant().getSegments()) {
            if (segment.getGeometry() != null && !segment.getGeometry().isEmpty()) {
                segments.add(segment.getGeometry());
            }
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("A seed tree must contain a pipe");
        return segments.get(0).getFactory().buildGeometry(segments);
    }

    private List<GroupPair> groupPairs(Variant tree,
            List<FeatureIndex.IndexedFeature> targets,
            List<FeatureIndex.IndexedFeature> validationInputs, Envelope searchArea) {
        Set<TypedId> connected = representedTargets(tree);
        List<FeatureIndex.IndexedFeature> eligible = new ArrayList<>();
        for (FeatureIndex.IndexedFeature target : targets) {
            if (target.getGeometry() instanceof Point && !connected.contains(target.getFeature().getId())
                    && searchArea.covers(target.getGeometry().getCoordinate())) eligible.add(target);
        }
        Map<TypedId, Point> rankingOrigins = new HashMap<>();
        for (FeatureIndex.IndexedFeature target : eligible) {
            rankingOrigins.put(target.getFeature().getId(),
                    singleTargetRouter.goalRankingOrigin(target, validationInputs, searchArea));
        }
        eligible.sort(Comparator
                .comparingDouble((FeatureIndex.IndexedFeature target) -> distanceToTree(
                        rankingOrigins.get(target.getFeature().getId()), tree))
                .thenComparing(target -> target.getFeature().getId(), TypedIdOrder.INSTANCE));
        List<FeatureIndex.IndexedFeature> spatialOrder = new ArrayList<>(eligible.size());
        for (int near = 0, far = eligible.size() - 1; near <= far; near++, far--) {
            spatialOrder.add(eligible.get(near));
            if (near < far) spatialOrder.add(eligible.get(far));
        }
        Map<TypedId, List<String>> corridors = new HashMap<>();
        for (FeatureIndex.IndexedFeature target : eligible) {
            List<String> ids = new ArrayList<>();
            for (TreeGoal goal : treeGoalsForSearch(tree, target, validationInputs, searchArea)) {
                if (goal.trunkId == null || ids.contains(goal.trunkId)) continue;
                ids.add(goal.trunkId);
                if (ids.size() >= 3) break;
            }
            corridors.put(target.getFeature().getId(), ids);
        }
        List<GroupPair> pairs = new ArrayList<>();
        Set<List<Object>> seen = new HashSet<>();
        for (int partnerRound = 0; partnerRound < 2 && pairs.size() < MAX_GROUP_PAIRS; partnerRound++) {
            for (FeatureIndex.IndexedFeature first : spatialOrder) {
                List<FeatureIndex.IndexedFeature> partners = new ArrayList<>(eligible);
                partners.remove(first);
                partners.sort(Comparator
                        .comparingDouble((FeatureIndex.IndexedFeature second) ->
                                first.getGeometry().distance(second.getGeometry()))
                        .thenComparing(second -> second.getFeature().getId(), TypedIdOrder.INSTANCE));
                int eligiblePartners = 0;
                for (FeatureIndex.IndexedFeature second : partners) {
                    String shared = null;
                    for (String trunkId : corridors.get(first.getFeature().getId())) {
                        if (corridors.get(second.getFeature().getId()).contains(trunkId)) {
                            shared = trunkId;
                            break;
                        }
                    }
                    if (shared == null || eligiblePartners++ < partnerRound) continue;
                    TypedId low = TypedIdOrder.INSTANCE.compare(first.getFeature().getId(),
                            second.getFeature().getId()) <= 0 ? first.getFeature().getId()
                                    : second.getFeature().getId();
                    TypedId high = low.equals(first.getFeature().getId())
                            ? second.getFeature().getId() : first.getFeature().getId();
                    List<Object> key = new ArrayList<>(3);
                    key.add(low);
                    key.add(high);
                    key.add(shared);
                    if (seen.add(key)) {
                        pairs.add(new GroupPair(first, second, shared));
                        break;
                    }
                }
                if (pairs.size() >= MAX_GROUP_PAIRS) break;
            }
        }
        List<GroupPair> primary = new ArrayList<>(pairs);
        for (GroupPair pair : primary) {
            if (pairs.size() >= MAX_GROUP_PAIRS) break;
            for (String alternative : corridors.get(pair.first.getFeature().getId())) {
                if (alternative.equals(pair.trunkId)
                        || !corridors.get(pair.second.getFeature().getId()).contains(alternative)) continue;
                TypedId firstId = pair.first.getFeature().getId();
                TypedId secondId = pair.second.getFeature().getId();
                List<Object> key = new ArrayList<>(3);
                key.add(TypedIdOrder.INSTANCE.compare(firstId, secondId) <= 0 ? firstId : secondId);
                key.add(TypedIdOrder.INSTANCE.compare(firstId, secondId) <= 0 ? secondId : firstId);
                key.add(alternative);
                if (seen.add(key)) pairs.add(new GroupPair(pair.first, pair.second, alternative));
                break;
            }
        }
        return pairs;
    }

    private static final class GroupPair {
        private final FeatureIndex.IndexedFeature first;
        private final FeatureIndex.IndexedFeature second;
        private final String trunkId;
        private GroupPair(FeatureIndex.IndexedFeature first,
                FeatureIndex.IndexedFeature second, String trunkId) {
            this.first = first;
            this.second = second;
            this.trunkId = trunkId;
        }
    }

    private static void notifyTreeTrajectory(RouteProgressListener listener, String trajectoryId,
            Variant checkedTree) {
        try {
            synchronized (listener) {
                listener.onTreeTrajectoryUpdated(trajectoryId, checkedTree);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Tree-trajectory progress observer failed; routing will continue", exception);
        }
    }

    private static String optimizationFailure(RouteTreeOptimizer.OptimizationResult result) {
        if (result.getValidation() == null || result.getValidation().getIssues().isEmpty()) {
            return result.getDetail();
        }
        ru.lct.heatnetwork.validation.ValidationIssue issue = result.getValidation().getIssues().get(0);
        String element = issue.getElementId() == null ? "" : " [" + issue.getElementId() + "]";
        return issue.getCode() + element + ": " + issue.getMessage();
    }

    private static void notifyTreeAttempt(RouteProgressListener listener, TypedId targetId,
            String attemptId, Point destination, RouteCandidate.Status status,
            String reason, Double lengthM) {
        try {
            synchronized (listener) {
                listener.onTreeAttemptCompleted(targetId, attemptId, destination, status, reason, lengthM);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Tree-attempt progress observer failed; routing will continue", exception);
        }
    }

    private static double distanceToTree(FeatureIndex.IndexedFeature target, Variant tree) {
        return distanceToTree(target.getGeometry(), tree);
    }

    private static int futureOpportunityCount(Variant parent, Variant candidate,
            List<FeatureIndex.IndexedFeature> targets) {
        Set<TypedId> connected = representedTargets(candidate);
        int opportunities = 0;
        for (FeatureIndex.IndexedFeature target : targets) {
            if (!(target.getGeometry() instanceof Point)
                    || connected.contains(target.getFeature().getId())) continue;
            if (distanceToTree(target, candidate) + 1.0 < distanceToTree(target, parent)) {
                opportunities++;
            }
        }
        return opportunities;
    }

    private static double distanceToTree(Geometry origin, Variant tree) {
        double distance = Double.POSITIVE_INFINITY;
        for (Variant.Segment segment : tree.getSegments()) {
            distance = Math.min(distance, segment.getGeometry().distance(origin));
        }
        return distance;
    }

    private FutureSeedOpportunity futureSeedOpportunity(Variant seed,
            List<FeatureIndex.IndexedFeature> targets,
            List<FeatureIndex.IndexedFeature> validationInputs, Envelope searchArea) {
        Set<TypedId> connected = representedTargets(seed);
        int safeTargets = 0;
        double proximity = 0;
        for (FeatureIndex.IndexedFeature target : targets) {
            if (!(target.getGeometry() instanceof Point)
                    || connected.contains(target.getFeature().getId())
                    || !searchArea.covers(target.getGeometry().getCoordinate())) continue;
            double distance = treeGoalsForSearch(seed, target, validationInputs, searchArea).stream()
                    .mapToDouble(goal -> goal.distance).min().orElse(Double.POSITIVE_INFINITY);
            if (Double.isFinite(distance)) {
                safeTargets++;
                proximity += 1.0 / (1.0 + distance / 100.0);
            }
        }
        return new FutureSeedOpportunity(safeTargets, proximity);
    }

    private static final class FutureSeedOpportunity {
        private final int safeTargetCount;
        private final double proximity;
        private FutureSeedOpportunity(int safeTargetCount, double proximity) {
            this.safeTargetCount = safeTargetCount;
            this.proximity = proximity;
        }
    }

    private static List<Variant.Segment> eligibleTrunks(Variant tree, FeatureIndex.IndexedFeature target) {
        return eligibleTrunks(tree, target, target.getGeometry());
    }

    private static List<Variant.Segment> eligibleTrunks(Variant tree,
            FeatureIndex.IndexedFeature target, Geometry origin) {
        List<Variant.Segment> trunks = new ArrayList<>();
        for (Variant.Segment segment : tree.getSegments()) {
            if ("base".equals(segment.getLayingMethod()) && segment.getRestrictionIds().isEmpty()
                    && segment.getGeometry() != null && segment.getGeometry().getNumPoints() >= 2) {
                trunks.add(segment);
            }
        }
        trunks.sort(Comparator
                .comparingDouble((Variant.Segment segment) -> segment.getGeometry().distance(origin))
                .thenComparing(Variant.Segment::getId));
        return trunks;
    }

    List<TreeGoal> treeGoalsForSearch(Variant tree, FeatureIndex.IndexedFeature target,
            List<FeatureIndex.IndexedFeature> validationInputs, Envelope searchArea) {
        Point origin = singleTargetRouter.goalRankingOrigin(target, validationInputs, searchArea);
        List<TreeGoal> baseline = treeGoals(tree, target, origin);
        BigDecimal addedDemand = targetDemandForGoalRanking(target);
        List<FeatureIndex.IndexedFeature> foreignOks = foreignOks(target, validationInputs);
        if (addedDemand == null || foreignOks.isEmpty()) return baseline;
        Map<String, Variant.Segment> segmentsById = new HashMap<>();
        Map<String, Variant.Segment> rootwardByNode = new HashMap<>();
        for (Variant.Segment segment : tree.getSegments()) {
            segmentsById.put(segment.getId(), segment);
            rootwardByNode.put(segment.getFlowFromNodeId(), segment);
        }
        ClearanceCheck clearance = new ClearanceCheck();
        List<TreeGoal> safe = new ArrayList<>();
        for (TreeGoal goal : baseline) {
            if (rootwardGoalIsClear(goal, segmentsById, rootwardByNode,
                    foreignOks, addedDemand, clearance)) safe.add(goal);
        }
        List<TreeGoal> distant = new ArrayList<>();
        for (Variant.Segment trunk : eligibleTrunks(tree, target, origin)) {
            if (distant.size() >= 3) break;
            TreeGoal goal = goalBeyondLastOksBottleneck(trunk, origin, foreignOks,
                    addedDemand, segmentsById, rootwardByNode, clearance);
            if (goal != null && safe.stream().noneMatch(previous -> previous.point.distance(goal.point) < 0.1)
                    && distant.stream().noneMatch(previous -> previous.point.distance(goal.point) < 0.1)) {
                distant.add(goal);
            }
        }
        distant.sort(Comparator.comparingDouble((TreeGoal goal) -> goal.distance)
                .thenComparing(goal -> goal.trunkId));
        distant.addAll(safe);
        return distant;
    }

    private static BigDecimal targetDemandForGoalRanking(FeatureIndex.IndexedFeature target) {
        JsonNode value = target.getFeature().getProperties().getExtra().get("flow_tph");
        return value != null && value.isNumber() && value.decimalValue().signum() > 0
                ? value.decimalValue() : null;
    }

    private static List<FeatureIndex.IndexedFeature> foreignOks(FeatureIndex.IndexedFeature target,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        List<FeatureIndex.IndexedFeature> result = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : validationInputs) {
            if (!"restriction".equals(input.getFeature().getObjectType())) continue;
            JsonNode type = input.getFeature().getProperties().getExtra().get("restriction_type");
            if (type != null && type.isTextual() && "oks".equals(type.textValue())
                    && !input.getGeometry().contains(target.getGeometry())) result.add(input);
        }
        return result;
    }

    private static int minimumFutureDiameter(Variant.Segment segment, BigDecimal addedDemand) {
        return RuleCatalog.smallestDiameter(segment.getFlowTph().add(addedDemand), BigDecimal.ZERO)
                .map(rule -> Math.max(segment.getDiameterMm(), rule.getDiameterMm())).orElse(-1);
    }

    private static boolean rootwardPieceIsClear(LineString piece, Variant.Segment segment,
            List<FeatureIndex.IndexedFeature> foreignOks, BigDecimal addedDemand,
            ClearanceCheck clearance) {
        int diameter = minimumFutureDiameter(segment, addedDemand);
        if (diameter < 0) return false;
        double threshold = clearance.requiredCenterlineDistanceM("oks", diameter,
                RuleCatalog.diameter(diameter).get().getPairWidthM(), null);
        for (FeatureIndex.IndexedFeature obstacle : foreignOks) {
            if (piece.getEnvelopeInternal().distance(obstacle.getGeometry().getEnvelopeInternal()) > threshold) continue;
            if (!clearance.respectsClearance(piece, obstacle.getGeometry(), threshold,
                    obstacle.getFeature().getId(), Collections.emptyList())) return false;
        }
        return true;
    }

    private static boolean rootwardGoalIsClear(TreeGoal goal,
            Map<String, Variant.Segment> segmentsById,
            Map<String, Variant.Segment> rootwardByNode,
            List<FeatureIndex.IndexedFeature> foreignOks, BigDecimal addedDemand,
            ClearanceCheck clearance) {
        String node = goal.chamberNodeId;
        if (goal.trunkId != null) {
            Variant.Segment trunk = segmentsById.get(goal.trunkId);
            if (trunk == null || !trunk.getFlowFromNodeId().equals(trunk.getStartNodeId())
                    || !trunk.getFlowToNodeId().equals(trunk.getEndNodeId())) return false;
            LineString geometry = trunk.getGeometry();
            LengthIndexedLine indexed = new LengthIndexedLine(geometry);
            double measure = indexed.project(goal.point.getCoordinate());
            LineString suffix = (LineString) indexed.extractLine(measure, geometry.getLength());
            suffix.setSRID(32637);
            if (!rootwardPieceIsClear(suffix, trunk, foreignOks, addedDemand, clearance)) return false;
            node = trunk.getFlowToNodeId();
        }
        Set<String> visited = new HashSet<>();
        while (rootwardByNode.containsKey(node)) {
            if (!visited.add(node)) return false;
            Variant.Segment segment = rootwardByNode.get(node);
            if (!rootwardPieceIsClear(segment.getGeometry(), segment,
                    foreignOks, addedDemand, clearance)) return false;
            node = segment.getFlowToNodeId();
        }
        return true;
    }

    private static boolean candidateGoalIsClear(Variant tree, FeatureIndex.IndexedFeature target,
            List<FeatureIndex.IndexedFeature> validationInputs, TreeGoal goal) {
        BigDecimal demand = targetDemandForGoalRanking(target);
        List<FeatureIndex.IndexedFeature> obstacles = foreignOks(target, validationInputs);
        if (demand == null || obstacles.isEmpty()) return true;
        Map<String, Variant.Segment> byId = new HashMap<>();
        Map<String, Variant.Segment> byConsumer = new HashMap<>();
        for (Variant.Segment segment : tree.getSegments()) {
            byId.put(segment.getId(), segment);
            byConsumer.put(segment.getFlowFromNodeId(), segment);
        }
        return rootwardGoalIsClear(goal, byId, byConsumer, obstacles, demand,
                new ClearanceCheck());
    }

    private static TreeGoal goalBeyondLastOksBottleneck(Variant.Segment trunk, Point origin,
            List<FeatureIndex.IndexedFeature> foreignOks, BigDecimal addedDemand,
            Map<String, Variant.Segment> segmentsById,
            Map<String, Variant.Segment> rootwardByNode, ClearanceCheck clearance) {
        LineString geometry = trunk.getGeometry();
        LengthIndexedLine indexed = new LengthIndexedLine(geometry);
        double projection = indexed.project(origin.getCoordinate());
        int diameter = minimumFutureDiameter(trunk, addedDemand);
        if (diameter < 0) return null;
        double threshold = clearance.requiredCenterlineDistanceM("oks", diameter,
                RuleCatalog.diameter(diameter).get().getPairWidthM(), null);
        double lastUnsafe = Double.NEGATIVE_INFINITY;
        for (FeatureIndex.IndexedFeature obstacle : foreignOks) {
            if (geometry.getEnvelopeInternal().distance(obstacle.getGeometry().getEnvelopeInternal()) > threshold) continue;
            Geometry contact = geometry.intersection(obstacle.getGeometry().buffer(threshold));
            for (Coordinate coordinate : contact.getCoordinates()) {
                lastUnsafe = Math.max(lastUnsafe, indexed.project(coordinate));
            }
        }
        if (!(lastUnsafe > projection + 0.1)) return null;
        for (double margin : new double[] {0.5, 1.0, 2.0, 5.0}) {
            double measure = lastUnsafe + margin;
            if (measure >= geometry.getLength() - 0.25) break;
            Coordinate coordinate = indexed.extractPoint(measure);
            double projected = indexed.project(coordinate);
            if (projected < 0.25 || geometry.getLength() - projected < 0.25) continue;
            boolean nearVertex = false;
            for (Coordinate vertex : geometry.getCoordinates()) {
                if (coordinate.distance(vertex) < 0.25) nearVertex = true;
            }
            if (nearVertex) continue;
            Point point = geometry.getFactory().createPoint(new Coordinate(coordinate));
            point.setSRID(32637);
            TreeGoal goal = TreeGoal.trunk(trunk.getId(), point, origin.distance(point));
            if (rootwardGoalIsClear(goal, segmentsById, rootwardByNode,
                    foreignOks, addedDemand, clearance)) return goal;
        }
        return null;
    }

    static List<TreeGoal> treeGoals(Variant tree, FeatureIndex.IndexedFeature target) {
        return treeGoals(tree, target, (Point) target.getGeometry());
    }

    private static List<TreeGoal> treeGoals(Variant tree, FeatureIndex.IndexedFeature target,
            Point origin) {
        List<List<TreeGoal>> byCorridor = new ArrayList<>();
        for (Variant.Node node : tree.getNodes()) {
            if (!"heat_chamber".equals(node.getType())) continue;
            long degree = tree.getSegments().stream().filter(segment ->
                    node.getId().equals(segment.getStartNodeId())
                            || node.getId().equals(segment.getEndNodeId())).count();
            if (degree == 0 || degree >= 4) continue;
            Point point = target.getGeometry().getFactory().createPoint(
                    new Coordinate(node.getX(), node.getY()));
            point.setSRID(32637);
            byCorridor.add(Collections.singletonList(TreeGoal.chamber(node.getId(), point,
                    origin.distance(point))));
        }
        List<Variant.Segment> trunks = eligibleTrunks(tree, target, origin);
        for (Variant.Segment trunk : trunks) {
            LineString geometry = trunk.getGeometry();
            LengthIndexedLine indexed = new LengthIndexedLine(geometry);
            double projection = indexed.project(origin.getCoordinate());
            List<TreeGoal> local = new ArrayList<>();
            for (double offset : new double[] {0.25, 0.0, -15.0, 15.0, -5.0, 5.0, -30.0, 30.0}) {
                double measure = projection + offset;
                if (measure < 0.25 || geometry.getLength() - measure < 0.25) continue;
                Coordinate coordinate = indexed.extractPoint(measure);
                double projected = indexed.project(coordinate);
                if (projected < 0.25 || geometry.getLength() - projected < 0.25) continue;
                boolean nearVertex = false;
                for (Coordinate vertex : geometry.getCoordinates()) {
                    if (coordinate.distance(vertex) < 0.25) nearVertex = true;
                }
                if (nearVertex) continue;
                Point point = geometry.getFactory().createPoint(new Coordinate(coordinate));
                point.setSRID(32637);
                if (local.stream().noneMatch(previous -> previous.point.distance(point) < 0.1)) {
                    local.add(TreeGoal.trunk(trunk.getId(), point,
                            origin.distance(point)));
                }
            }
            if (!local.isEmpty()) byCorridor.add(local);
        }
        byCorridor.sort(Comparator.comparingDouble((List<TreeGoal> goals) -> goals.get(0).distance)
                .thenComparing(goals -> goals.get(0).chamberNodeId == null
                        ? goals.get(0).trunkId : goals.get(0).chamberNodeId));
        List<TreeGoal> goals = new ArrayList<>();
        for (int offsetIndex = 0; offsetIndex < 8; offsetIndex++) {
            for (List<TreeGoal> local : byCorridor) {
                if (offsetIndex < local.size()) goals.add(local.get(offsetIndex));
            }
        }
        return goals;
    }

    private static String treeGoalKey(TreeGoal goal) {
        if (goal.chamberNodeId != null) return "chamber:" + goal.chamberNodeId;
        return "trunk:" + goal.trunkId + ":"
                + Math.round(goal.point.getX() * 100.0) + ":"
                + Math.round(goal.point.getY() * 100.0);
    }

    static final class TreeGoal {
        private final String trunkId;
        private final String chamberNodeId;
        private final Point point;
        private final double distance;
        private TreeGoal(String trunkId, String chamberNodeId, Point point, double distance) {
            this.trunkId = trunkId;
            this.chamberNodeId = chamberNodeId;
            this.point = point;
            this.distance = distance;
        }
        private static TreeGoal trunk(String trunkId, Point point, double distance) {
            return new TreeGoal(trunkId, null, point, distance);
        }
        private static TreeGoal chamber(String nodeId, Point point, double distance) {
            return new TreeGoal(null, nodeId, point, distance);
        }
        String getTrunkId() { return trunkId; }
        String getChamberNodeId() { return chamberNodeId; }
        Point getPoint() { return (Point) point.copy(); }
    }

    private void enumerateFoundChoices(List<TargetChoices> choices, int index, List<RouteChoice> selected,
            List<FeatureIndex.IndexedFeature> validationInputs, SearchCounter counter,
            List<RouteTreeOptimizer.TreeCandidate> accepted, Map<Integer, Integer> acceptedBySize,
            Map<String, Integer> rejectedFiveOrMore) {
        if (counter.limitReached) return;
        if (index == choices.size()) {
            if (selected.isEmpty()) return;
            if (counter.evaluated >= counter.maximum) {
                counter.limitReached = true;
                return;
            }
            counter.evaluated++;
            List<Variant> routes = new ArrayList<>(selected.size());
            List<AttachmentCandidate> attachments = new ArrayList<>(selected.size());
            for (RouteChoice choice : selected) {
                routes.add(choice.variant);
                attachments.add(choice.attachment);
            }
            RouteTreeOptimizer.OptimizationResult result = optimizer.optimize(
                    "exhaustive:" + counter.evaluated, routes, attachments, validationInputs);
            if (result.isFound()) {
                accepted.add(result.getCandidate());
                acceptedBySize.merge(selected.size(), 1, Integer::sum);
            } else if (selected.size() >= 5) {
                String reason = result.getValidation() != null && !result.getValidation().getIssues().isEmpty()
                        ? result.getValidation().getIssues().get(0).getCode()
                        : result.getDetail();
                rejectedFiveOrMore.merge(reason == null ? "UNKNOWN" : reason, 1, Integer::sum);
            }
            return;
        }
        for (RouteChoice choice : choices.get(index).routes) {
            selected.add(choice);
            enumerateFoundChoices(choices, index + 1, selected, validationInputs, counter,
                    accepted, acceptedBySize, rejectedFiveOrMore);
            selected.remove(selected.size() - 1);
            if (counter.limitReached) return;
        }
        enumerateFoundChoices(choices, index + 1, selected, validationInputs, counter,
                accepted, acceptedBySize, rejectedFiveOrMore);
    }

    private ForestState optimizeAddition(ForestState state, RouteChoice choice,
            List<FeatureIndex.IndexedFeature> validationInputs, SearchCounter counter) {
        List<RouteChoice> proposed = new ArrayList<>(state.choices);
        proposed.add(choice);
        return optimizeForest(proposed, validationInputs, counter);
    }

    private ForestState optimizeForest(List<RouteChoice> choices,
            List<FeatureIndex.IndexedFeature> validationInputs, SearchCounter counter) {
        if (counter.evaluated >= counter.maximum) {
            counter.limitReached = true;
            return null;
        }
        counter.evaluated++;
        List<Variant> routes = new ArrayList<>(choices.size());
        List<AttachmentCandidate> attachments = new ArrayList<>(choices.size());
        for (RouteChoice choice : choices) {
            routes.add(choice.variant);
            attachments.add(choice.attachment);
        }
        RouteTreeOptimizer.OptimizationResult result = optimizer.optimize(
                "adaptive:" + counter.evaluated, routes, attachments, validationInputs);
        return result.isFound() ? new ForestState(choices, result.getCandidate()) : null;
    }

    private static List<ForestState> selectBeam(List<ForestState> candidates, int maximum) {
        Map<String, ForestState> unique = new LinkedHashMap<>();
        for (ForestState candidate : candidates) unique.putIfAbsent(candidate.signature(), candidate);
        List<ForestState> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparingInt((ForestState state) -> state.choices.size()).reversed()
                .thenComparingDouble(state -> state.candidate == null
                        ? Double.POSITIVE_INFINITY : state.candidate.getTotalLengthM())
                .thenComparing(ForestState::signature));
        if (ordered.size() > maximum) return new ArrayList<>(ordered.subList(0, maximum));
        return ordered;
    }

    private static int choiceIndex(ForestState state, TypedId targetId) {
        for (int i = 0; i < state.choices.size(); i++) {
            if (representedTargets(state.choices.get(i).variant).contains(targetId)) return i;
        }
        return -1;
    }

    private static Set<TypedId> representedTargets(Variant variant) {
        Set<TypedId> result = new HashSet<>();
        for (Variant.Node node : variant.getNodes()) {
            if ("oks_connection_point".equals(node.getType()) && node.getInputFeatureId() != null) {
                result.add(node.getInputFeatureId());
            }
        }
        return result;
    }

    private static int connectedTargetCount(Variant variant) { return representedTargets(variant).size(); }

    private static boolean hasMultipleRoots(Variant variant) {
        Set<String> outflowNodes = new HashSet<>();
        for (Variant.Segment segment : variant.getSegments()) {
            outflowNodes.add(segment.getFlowFromNodeId());
        }
        int roots = 0;
        for (Variant.Node node : variant.getNodes()) {
            if ("heat_chamber".equals(node.getType()) && node.getInputFeatureId() != null
                    && !outflowNodes.contains(node.getId()) && ++roots > 1) return true;
        }
        return false;
    }

    static <T> List<T> executeSlidingWindow(List<? extends Callable<T>> tasks, ExecutorService executor,
            int maximumParallelSearches, IntConsumer onTaskSettled) {
        if (tasks == null || executor == null || maximumParallelSearches < 1 || maximumParallelSearches > 8
                || onTaskSettled == null) {
            throw new IllegalArgumentException("Tasks, an executor, a limit from one to four and a completion callback are required");
        }
        CompletionService<IndexedResult<T>> completions = new ExecutorCompletionService<>(executor);
        Map<Future<IndexedResult<T>>, Integer> taskIndexes = new HashMap<>();
        List<Future<IndexedResult<T>>> inFlight = new ArrayList<>();
        List<T> results = new ArrayList<>(Collections.nCopies(tasks.size(), null));
        int submitted = 0;
        int completed = 0;
        Throwable firstFailure = null;
        while (completed < submitted || (firstFailure == null && submitted < tasks.size())) {
            while (firstFailure == null && submitted < tasks.size() && inFlight.size() < maximumParallelSearches) {
                final int taskIndex = submitted++;
                Future<IndexedResult<T>> future = completions.submit(() ->
                        new IndexedResult<>(taskIndex, tasks.get(taskIndex).call()));
                inFlight.add(future);
                taskIndexes.put(future, taskIndex);
            }
            if (inFlight.isEmpty()) break;
            Future<IndexedResult<T>> completedFuture;
            try {
                completedFuture = completions.take();
            } catch (InterruptedException interrupted) {
                for (Future<?> future : inFlight) future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Route candidate search was interrupted", interrupted);
            }
            inFlight.remove(completedFuture);
            Integer taskIndex = taskIndexes.remove(completedFuture);
            completed++;
            try {
                IndexedResult<T> result = completedFuture.get();
                results.set(result.index, result.value);
            } catch (InterruptedException interrupted) {
                for (Future<?> future : inFlight) future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Route candidate result collection was interrupted", interrupted);
            } catch (ExecutionException failed) {
                if (firstFailure == null) firstFailure = failed.getCause();
            } finally {
                onTaskSettled.accept(taskIndex);
            }
        }
        if (firstFailure instanceof RuntimeException) throw (RuntimeException) firstFailure;
        if (firstFailure instanceof Error) throw (Error) firstFailure;
        if (firstFailure != null) throw new IllegalStateException("Route candidate search failed", firstFailure);
        return results;
    }

    private static RouteRequest forSingleTarget(RouteRequest request, TypedId targetId) {
        List<FeatureIndex.IndexedFeature> localInputs = new ArrayList<>();
        for (FeatureIndex.IndexedFeature indexed : request.getInputs()) {
            InputFeature feature = indexed.getFeature();
            if (!"oks_connection_point".equals(feature.getObjectType()) || targetId.equals(feature.getId())) {
                localInputs.add(indexed);
            }
        }
        return new RouteRequest(request.getVariantId(), request.getTarget(), request.getAttachment(), localInputs,
                request.getSearchArea(), request.getMaximumVisibilityVertices(), request.getMandatoryVia(),
                request.getPlannedSectionFlowTph());
    }

    private static MultiTargetRouteResult invalid(String detail) {
        return new MultiTargetRouteResult(MultiTargetRouteResult.Status.INVALID_REQUEST,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), 0, false, detail);
    }

    private static void notifyAttempt(RouteProgressListener listener, RouteRequest request, RouteCandidate candidate) {
        try {
            listener.onAttemptCompleted(request, candidate);
        } catch (RuntimeException failure) {
            LOG.warn("Route-progress observer failed; routing will continue", failure);
        }
    }

    private static void notifyCombinationStarted(RouteProgressListener listener) {
        try {
            listener.onCombinationStarted();
        } catch (RuntimeException failure) {
            LOG.warn("Route-progress observer failed; routing will continue", failure);
        }
    }

    private static final class TargetChoices {
        private final TypedId targetId;
        private final FeatureIndex.IndexedFeature feature;
        private final List<RouteChoice> routes;
        private TargetChoices(TypedId targetId, FeatureIndex.IndexedFeature feature, List<RouteChoice> routes) {
            this.targetId = targetId;
            this.feature = feature;
            this.routes = routes;
        }
    }

    private static final class BranchGrowth {
        private final RouteTreeOptimizer.TreeCandidate candidate;
        private final int attempted;
        private final int directAttempted;
        private final int attached;
        private final Map<String, Integer> rejected;
        private final boolean searchIncomplete;
        private final Map<TypedId, String> unsearchedReasons;
        private final List<RouteTreeOptimizer.TreeCandidate> portfolioSeeds;
        private BranchGrowth(RouteTreeOptimizer.TreeCandidate candidate, int attempted,
                int attached, Map<String, Integer> rejected) {
            this(candidate, attempted, 0, attached, rejected, false, Collections.emptyMap());
        }
        private BranchGrowth(RouteTreeOptimizer.TreeCandidate candidate, int attempted, int directAttempted,
                int attached, Map<String, Integer> rejected, boolean searchIncomplete,
                Map<TypedId, String> unsearchedReasons) {
            this(candidate, attempted, directAttempted, attached, rejected, searchIncomplete,
                    unsearchedReasons, Collections.emptyList());
        }
        private BranchGrowth(RouteTreeOptimizer.TreeCandidate candidate, int attempted, int directAttempted,
                int attached, Map<String, Integer> rejected, boolean searchIncomplete,
                Map<TypedId, String> unsearchedReasons,
                List<RouteTreeOptimizer.TreeCandidate> portfolioSeeds) {
            this.candidate = candidate;
            this.attempted = attempted;
            this.directAttempted = directAttempted;
            this.attached = attached;
            this.rejected = rejected;
            this.searchIncomplete = searchIncomplete;
            this.unsearchedReasons = Collections.unmodifiableMap(new LinkedHashMap<>(unsearchedReasons));
            this.portfolioSeeds = Collections.unmodifiableList(new ArrayList<>(portfolioSeeds));
        }
    }

    private static final class NavigatorGrowthSpec {
        private final RouteTreeOptimizer.TreeCandidate seed;
        private final BranchChoicePolicy policy;
        private final String trajectoryId;
        private final int maximumAttempts;
        private final boolean publishInitial;
        private final List<FeatureIndex.IndexedFeature> sectionTargets;

        private NavigatorGrowthSpec(RouteTreeOptimizer.TreeCandidate seed, BranchChoicePolicy policy,
                String trajectoryId, int maximumAttempts, boolean publishInitial,
                List<FeatureIndex.IndexedFeature> sectionTargets) {
            this.seed = seed;
            this.policy = policy;
            this.trajectoryId = trajectoryId;
            this.maximumAttempts = maximumAttempts;
            this.publishInitial = publishInitial;
            this.sectionTargets = sectionTargets;
        }
    }

    private static final class NavigatorGrowthResult {
        private final BranchGrowth growth;
        private final Map<TypedId, List<RouteCandidate.Status>> attempts;

        private NavigatorGrowthResult(BranchGrowth growth,
                Map<TypedId, List<RouteCandidate.Status>> attempts) {
            this.growth = growth;
            this.attempts = attempts;
        }
    }

    private List<NavigatorGrowthResult> growNavigatorStage(List<NavigatorGrowthSpec> specs,
            List<FeatureIndex.IndexedFeature> targets, List<FeatureIndex.IndexedFeature> validationInputs,
            Envelope searchArea, int maximumVisibilityVertices, int remainingBudget,
            ExecutorService executor, int maximumParallelSearches, RouteProgressListener listener) {
        int reserved = 0;
        for (NavigatorGrowthSpec spec : specs) reserved += spec.maximumAttempts;
        if (executor != null && maximumParallelSearches > 1 && reserved <= remainingBudget) {
            List<Callable<NavigatorGrowthResult>> calls = new ArrayList<>(specs.size());
            for (NavigatorGrowthSpec spec : specs) {
                calls.add(() -> growNavigatorScenario(spec, spec.maximumAttempts, targets,
                        validationInputs, searchArea, maximumVisibilityVertices, listener));
            }
            return executeNavigatorGrowthWave(calls, executor, maximumParallelSearches);
        }
        List<NavigatorGrowthResult> results = new ArrayList<>(specs.size());
        int used = 0;
        for (NavigatorGrowthSpec spec : specs) {
            int budget = Math.min(spec.maximumAttempts, remainingBudget - used);
            if (budget <= 0) break;
            NavigatorGrowthResult result = growNavigatorScenario(spec, budget, targets,
                    validationInputs, searchArea, maximumVisibilityVertices, listener);
            used += result.growth.attempted;
            results.add(result);
        }
        return results;
    }

    private NavigatorGrowthResult growNavigatorScenario(NavigatorGrowthSpec spec, int budget,
            List<FeatureIndex.IndexedFeature> targets, List<FeatureIndex.IndexedFeature> validationInputs,
            Envelope searchArea, int maximumVisibilityVertices, RouteProgressListener listener) {
        Map<TypedId, List<RouteCandidate.Status>> localAttempts = new LinkedHashMap<>();
        if (spec.policy == BranchChoicePolicy.GROUP_LOOKAHEAD && !spec.sectionTargets.isEmpty()) {
            notifyTreeTrajectory(listener, spec.trajectoryId, spec.seed.getVariant());
            int sectionBudget = Math.min(budget, Math.min(40, Math.max(8, budget / 2)));
            BranchGrowth section = growNavigatorBranches(spec.seed, spec.sectionTargets,
                    validationInputs, searchArea, maximumVisibilityVertices, sectionBudget,
                    localAttempts, listener, BranchChoicePolicy.SHORTEST_VALID,
                    spec.trajectoryId + ":district", true);
            RouteTreeOptimizer.TreeCandidate checkpoint = section.candidate == null
                    ? spec.seed : section.candidate;
            BranchGrowth remaining = growNavigatorGroup(checkpoint, targets, validationInputs,
                    searchArea, maximumVisibilityVertices, budget - section.attempted,
                    localAttempts, listener, spec.trajectoryId);
            Map<String, Integer> rejected = new TreeMap<>(section.rejected);
            remaining.rejected.forEach((reason, count) -> rejected.merge(reason, count, Integer::sum));
            BranchGrowth combined = new BranchGrowth(remaining.candidate != null ? remaining.candidate
                    : section.candidate, section.attempted + remaining.attempted,
                    section.directAttempted + remaining.directAttempted,
                    section.attached + remaining.attached, rejected,
                    section.searchIncomplete || remaining.searchIncomplete,
                    remaining.unsearchedReasons, remaining.portfolioSeeds);
            return new NavigatorGrowthResult(combined, localAttempts);
        }
        BranchGrowth growth = spec.policy == BranchChoicePolicy.GROUP_LOOKAHEAD
                ? growNavigatorGroup(spec.seed, targets, validationInputs, searchArea,
                        maximumVisibilityVertices, budget, localAttempts, listener, spec.trajectoryId)
                : growNavigatorBranches(spec.seed, targets, validationInputs, searchArea,
                        maximumVisibilityVertices, budget, localAttempts, listener, spec.policy,
                        spec.trajectoryId, spec.publishInitial);
        return new NavigatorGrowthResult(growth, localAttempts);
    }

    private static void mergeNavigatorAttempts(Map<TypedId, List<RouteCandidate.Status>> destination,
            Map<TypedId, List<RouteCandidate.Status>> source) {
        for (Map.Entry<TypedId, List<RouteCandidate.Status>> entry : source.entrySet()) {
            destination.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
        }
    }

    static <T> List<T> executeNavigatorGrowthWave(List<? extends Callable<T>> tasks,
            ExecutorService executor, int maximumParallelSearches) {
        return executeSlidingWindow(tasks, executor, maximumParallelSearches, ignored -> { });
    }

    private enum BranchChoicePolicy { GROUP_LOOKAHEAD, FIRST_VALID, SHORTEST_VALID }

    /** Front-loads long corridors with nearby downstream demand, then widens if no seed is valid. */
    /** Adds bounded district hypotheses before the first pipe; the ordinary seed remains a control. */
    private List<List<RouteRequest>> withDistrictSeedCorridors(List<List<RouteRequest>> alternatives,
            List<FeatureIndex.IndexedFeature> allTargets,
            List<FeatureIndex.IndexedFeature> validationInputs, Envelope searchArea,
            Map<TypedId, List<TypedId>> districtMembersByAnchor) {
        if (allTargets == null || allTargets.size() < 2 || allTargets.size() > 128
                || alternatives.size() > 128 || searchArea == null || searchArea.isNull()) return alternatives;
        Map<TypedId, Integer> indexes = new HashMap<>();
        List<List<RouteRequest>> expanded = new ArrayList<>(alternatives.size());
        for (int index = 0; index < alternatives.size(); index++) {
            List<RouteRequest> requests = alternatives.get(index);
            if (requests == null || requests.isEmpty() || requests.get(0) == null
                    || requests.get(0).getTarget() == null
                    || requests.get(0).getTarget().getFeature() == null) return alternatives;
            expanded.add(new ArrayList<>(requests));
            indexes.put(requests.get(0).getTarget().getFeature().getId(), index);
        }
        List<DemandGroupPlanner.Demand> demands = new ArrayList<>();
        List<DemandGroupPlanner.RootOption> roots = new ArrayList<>();
        Map<TypedId, Point> exits = new HashMap<>();
        for (FeatureIndex.IndexedFeature target : allTargets) {
            TypedId id = target.getFeature().getId();
            Integer index = indexes.get(id);
            if (index == null || !(target.getGeometry() instanceof Point)) continue;
            JsonNode flow = target.getFeature().getProperties().getExtra().get("flow_tph");
            if (flow == null || !flow.isNumber() || flow.decimalValue().signum() <= 0) continue;
            Point exit = singleTargetRouter.goalRankingOrigin(target, validationInputs, searchArea);
            exits.put(id, exit);
            demands.add(new DemandGroupPlanner.Demand(id, exit, flow.decimalValue()));
            RouteRequest first = expanded.get(index).get(0);
            roots.add(new DemandGroupPlanner.RootOption(TypedId.of("district-root-" + index),
                    first.getAttachment().getNetworkId(), first.getAttachment().getEndpoint()));
        }
        if (demands.size() < 2) return alternatives;
        BigDecimal maximumFlow = RuleCatalog.diameters().get(RuleCatalog.diameters().size() - 1)
                .getCapacityTph();
        DemandGroupPlanner.Limits limits = new DemandGroupPlanner.Limits(128, 128, 4, 5, 64,
                512, 1024, 2, maximumFlow);
        DemandGroupPlanner.PassageProbe proximity = new DemandGroupPlanner.PassageProbe() {
            @Override
            public DemandGroupPlanner.Passage betweenExits(DemandGroupPlanner.Demand first,
                    DemandGroupPlanner.Demand second) {
                return DemandGroupPlanner.Passage.possible(first.getProjectedExit()
                        .distance(second.getProjectedExit()));
            }

            @Override
            public DemandGroupPlanner.Passage toRoot(DemandGroupPlanner.Demand target,
                    DemandGroupPlanner.RootOption root, BigDecimal groupFlowTph) {
                return DemandGroupPlanner.Passage.possible(target.getProjectedExit()
                        .distance(root.getProjectedEndpoint()));
            }
        };
        DemandGroupPlanner.Plan plan = new DemandGroupPlanner().plan(demands, roots, proximity, limits);
        districtMembersByAnchor.putAll(sectionMembersBySeed(plan));
        Map<TypedId, BigDecimal> demandById = new HashMap<>();
        for (DemandGroupPlanner.Demand demand : demands) {
            demandById.put(demand.getId(), demand.getFlowTph());
        }
        Map<TypedId, BigDecimal> plannedFlows = new HashMap<>();
        for (Map.Entry<TypedId, List<TypedId>> entry : districtMembersByAnchor.entrySet()) {
            BigDecimal flow = BigDecimal.ZERO;
            for (TypedId member : entry.getValue()) flow = flow.add(demandById.get(member));
            plannedFlows.put(entry.getKey(), flow);
            RouteRequest ordinary = expanded.get(indexes.get(entry.getKey())).get(0);
            expanded.get(indexes.get(entry.getKey())).add(new RouteRequest(ordinary.getVariantId()
                    + ":section-reserve", ordinary.getTarget(), ordinary.getAttachment(), ordinary.getInputs(),
                    ordinary.getSearchArea(), ordinary.getMaximumVisibilityVertices(),
                    ordinary.getMandatoryVia(), flow));
        }
        List<Point> selectedAnchors = new ArrayList<>();
        int added = 0;
        for (int groupIndex = 0; groupIndex < 2; groupIndex++) {
            DemandGroupPlanner.Group best = null;
            RouteRequest bestRequest = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (DemandGroupPlanner.Group group : plan.getGroups()) {
                RouteRequest anchor = null;
                double reach = Double.NEGATIVE_INFINITY;
                for (TypedId id : group.getTargetIds()) {
                    RouteRequest request = expanded.get(indexes.get(id)).get(0);
                    Point exit = exits.get(id);
                    if (selectedAnchors.stream().anyMatch(selected -> selected.distance(exit) < 1.0)) continue;
                    double distance = exit.distance(request.getAttachment().getEndpoint());
                    if (distance > reach) {
                        reach = distance;
                        anchor = request;
                    }
                }
                if (anchor == null) continue;
                Point anchorExit = exits.get(anchor.getTarget().getFeature().getId());
                double separation = selectedAnchors.isEmpty() ? 0 : selectedAnchors.stream()
                        .mapToDouble(selected -> selected.distance(anchorExit)).min().orElse(0);
                double score = reach + separation + 20.0 * group.getTargetIds().size();
                if (score > bestScore) {
                    best = group;
                    bestRequest = anchor;
                    bestScore = score;
                }
            }
            if (best == null) break;
            TypedId anchorId = bestRequest.getTarget().getFeature().getId();
            Point anchorExit = exits.get(anchorId);
            selectedAnchors.add(anchorExit);
            double centerX = 0, centerY = 0;
            for (TypedId id : best.getTargetIds()) {
                centerX += exits.get(id).getX();
                centerY += exits.get(id).getY();
            }
            centerX /= best.getTargetIds().size();
            centerY /= best.getTargetIds().size();
            Point root = bestRequest.getAttachment().getEndpoint();
            double dx = centerX - root.getX(), dy = centerY - root.getY();
            double length = Math.hypot(dx, dy);
            if (length < 1.0) {
                dx = anchorExit.getX() - root.getX();
                dy = anchorExit.getY() - root.getY();
                length = Math.hypot(dx, dy);
            }
            if (length < 1.0) continue;
            List<Point> corridorPoints = new ArrayList<>();
            for (double offset : new double[] {0, 12, -12, 24, -24, 36, -36}) {
                Coordinate coordinate = new Coordinate(centerX - dy / length * offset,
                        centerY + dx / length * offset);
                Point via = anchorExit.getFactory().createPoint(coordinate);
                if (!searchArea.covers(coordinate) || via.distance(anchorExit) < 1.0
                        || via.distance(root) < 1.0
                        || corridorPoints.stream().anyMatch(point -> point.distance(via) < 8.0)
                        || !preliminarilyFreeCorridorPoint(via, validationInputs)) continue;
                corridorPoints.add(via);
                if (corridorPoints.size() == 2) break;
            }
            Integer anchorIndex = indexes.get(anchorId);
            for (int corridorIndex = 0; corridorIndex < corridorPoints.size(); corridorIndex++) {
                Point via = corridorPoints.get(corridorIndex);
                RouteRequest corridor = new RouteRequest(bestRequest.getVariantId()
                        + ":district:" + groupIndex + ":corridor:" + corridorIndex,
                        bestRequest.getTarget(), bestRequest.getAttachment(), bestRequest.getInputs(),
                        bestRequest.getSearchArea(), bestRequest.getMaximumVisibilityVertices(), via,
                        plannedFlows.get(anchorId));
                expanded.get(anchorIndex).add(corridor);
                added++;
            }
        }
        LOG.info("Navigator district preplan groups={} pairProbes={} rootProbes={} corridorSeeds={} preliminaryOnly=true",
                plan.getGroups().size(), plan.getPairProbeCount(), plan.getRootProbeCount(), added);
        return expanded;
    }

    /** A failed corridor seed does not remove its group from another member's valid trunk. */
    static Map<TypedId, List<TypedId>> sectionMembersBySeed(DemandGroupPlanner.Plan plan) {
        Map<TypedId, List<TypedId>> sections = new LinkedHashMap<>();
        for (DemandGroupPlanner.Group group : plan.getGroups()) {
            for (TypedId id : group.getTargetIds()) {
                List<TypedId> previous = sections.get(id);
                if (previous == null || group.getTargetIds().size() > previous.size()) {
                    sections.put(id, group.getTargetIds());
                }
            }
        }
        return sections;
    }

    private static boolean preliminarilyFreeCorridorPoint(Point via,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        for (FeatureIndex.IndexedFeature input : validationInputs) {
            if (!"restriction".equals(input.getFeature().getObjectType())) continue;
            JsonNode type = input.getFeature().getProperties().getExtra().get("restriction_type");
            if (type == null || !type.isTextual()) continue;
            String value = type.asText();
            if (("oks".equals(value) || "park".equals(value) || "water".equals(value)
                    || "railway".equals(value) || "prohibited_site".equals(value)
                    || "social_area".equals(value)) && input.getGeometry().distance(via) < 5.5) {
                return false;
            }
        }
        return true;
    }

    private static List<SearchTask> orderedSeedTasks(List<SearchTask> tasks,
            List<FeatureIndex.IndexedFeature> allTargets) {
        List<FeatureIndex.IndexedFeature> targets = allTargets == null
                ? Collections.emptyList() : allTargets;
        Map<Integer, Double> priorities = new HashMap<>();
        for (SearchTask task : tasks) {
            if (priorities.containsKey(task.targetIndex)) continue;
            Point start = task.request.getTargetPoint();
            Point root = task.request.getAttachment().getEndpoint();
            double distance = start.distance(root);
            LineString corridor = start.getFactory().createLineString(
                    new Coordinate[] {start.getCoordinate(), root.getCoordinate()});
            double potential = 0;
            for (FeatureIndex.IndexedFeature target : targets) {
                if (!(target.getGeometry() instanceof Point)
                        || target.getFeature().getId().equals(task.request.getTarget().getFeature().getId())) {
                    continue;
                }
                double offset = corridor.distance(target.getGeometry());
                if (offset < 40.0 && root.distance(target.getGeometry()) + 2.0 < distance) {
                    potential += 1.0 - offset / 40.0;
                }
            }
            priorities.put(task.targetIndex, distance + 80.0 * potential);
        }
        Map<Integer, SearchTask> representatives = new LinkedHashMap<>();
        for (SearchTask task : tasks) representatives.putIfAbsent(task.targetIndex, task);
        List<SearchTask> remaining = new ArrayList<>(representatives.values());
        List<SearchTask> spatialOrder = new ArrayList<>();
        while (!remaining.isEmpty()) {
            remaining.sort((left, right) -> {
                if (!spatialOrder.isEmpty()) {
                    double leftSpread = spatialOrder.stream().mapToDouble(previous ->
                            left.request.getTargetPoint().distance(previous.request.getTargetPoint()))
                            .min().orElse(0);
                    double rightSpread = spatialOrder.stream().mapToDouble(previous ->
                            right.request.getTargetPoint().distance(previous.request.getTargetPoint()))
                            .min().orElse(0);
                    int spreadOrder = Double.compare(rightSpread, leftSpread);
                    if (spreadOrder != 0) return spreadOrder;
                }
                int priorityOrder = Double.compare(priorities.get(right.targetIndex),
                        priorities.get(left.targetIndex));
                if (priorityOrder != 0) return priorityOrder;
                return TypedIdOrder.INSTANCE.compare(left.request.getTarget().getFeature().getId(),
                        right.request.getTarget().getFeature().getId());
            });
            spatialOrder.add(remaining.remove(0));
        }
        Map<Integer, Integer> spatialRank = new HashMap<>();
        for (int index = 0; index < spatialOrder.size(); index++) {
            spatialRank.put(spatialOrder.get(index).targetIndex, index);
        }
        List<SearchTask> ordered = new ArrayList<>(tasks);
        ordered.sort((left, right) -> {
            int result = Integer.compare(spatialRank.get(left.targetIndex), spatialRank.get(right.targetIndex));
            if (result != 0) return result;
            int corridorOrder = Boolean.compare(right.request.getMandatoryVia() != null,
                    left.request.getMandatoryVia() != null);
            if (corridorOrder != 0) return corridorOrder;
            int reserveOrder = Boolean.compare(right.request.getPlannedSectionFlowTph() != null,
                    left.request.getPlannedSectionFlowTph() != null);
            if (reserveOrder != 0) return reserveOrder;
            result = Integer.compare(left.alternativeIndex, right.alternativeIndex);
            if (result != 0) return result;
            return TypedIdOrder.INSTANCE.compare(left.request.getTarget().getFeature().getId(),
                    right.request.getTarget().getFeature().getId());
        });
        return ordered;
    }

    private static final class SearchTask {
        private final int targetIndex;
        private final int alternativeIndex;
        private final RouteRequest request;
        private SearchTask(int targetIndex, int alternativeIndex, RouteRequest request) {
            this.targetIndex = targetIndex;
            this.alternativeIndex = alternativeIndex;
            this.request = request;
        }
    }

    private static final class IndexedResult<T> {
        private final int index;
        private final T value;
        private IndexedResult(int index, T value) { this.index = index; this.value = value; }
    }

    private static final class RouteChoice {
        private final Variant variant;
        private final AttachmentCandidate attachment;
        private RouteChoice(Variant variant, AttachmentCandidate attachment) {
            this.variant = variant;
            this.attachment = attachment;
        }
    }

    private static final class SearchCounter {
        private final int maximum;
        private int evaluated;
        private boolean limitReached;
        private SearchCounter(int maximum) { this.maximum = maximum; }
    }

    private static final class ForestState {
        private final List<RouteChoice> choices;
        private final RouteTreeOptimizer.TreeCandidate candidate;
        private ForestState(List<RouteChoice> choices, RouteTreeOptimizer.TreeCandidate candidate) {
            this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
            this.candidate = candidate;
        }
        private String signature() {
            StringBuilder result = new StringBuilder();
            for (RouteChoice choice : choices) {
                result.append(choice.variant.getId()).append('|')
                        .append(choice.attachment.getNetworkId()).append('|')
                        .append(choice.attachment.getEndpoint().getX()).append(',')
                        .append(choice.attachment.getEndpoint().getY()).append(';');
            }
            return result.toString();
        }
    }

    private static final class AdaptiveCombinationResult {
        private final List<RouteTreeOptimizer.TreeCandidate> candidates;
        private final SearchCounter counter;
        private AdaptiveCombinationResult(List<RouteTreeOptimizer.TreeCandidate> candidates, SearchCounter counter) {
            this.candidates = candidates;
            this.counter = counter;
        }
    }
}
