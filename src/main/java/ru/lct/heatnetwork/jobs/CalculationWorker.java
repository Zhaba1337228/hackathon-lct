package ru.lct.heatnetwork.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnetwork.api.CalculationResultResponse;
import ru.lct.heatnetwork.costing.CostedVariant;
import ru.lct.heatnetwork.costing.VariantCostCalculator;
import ru.lct.heatnetwork.costing.VariantCostSummary;
import ru.lct.heatnetwork.costing.VariantSelector;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.TypedIdOrder;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.network.ExistingHeatChamber;
import ru.lct.heatnetwork.network.ExistingHeatNetwork;
import ru.lct.heatnetwork.network.RouteTreeOptimizer;
import ru.lct.heatnetwork.output.GeoJsonExportTooLargeException;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.routing.ExistingNetworkConnector;
import ru.lct.heatnetwork.routing.MultiTargetRouteResult;
import ru.lct.heatnetwork.routing.MultiTargetRouter;
import ru.lct.heatnetwork.routing.RouteCandidate;
import ru.lct.heatnetwork.routing.RouteProgressListener;
import ru.lct.heatnetwork.routing.RouteRequest;
import ru.lct.heatnetwork.routing.SingleTargetRouter;
import ru.lct.heatnetwork.spatial.FeatureIndex;

/** Executes an accepted search, preserving explicit partial/no-route outcomes. */
@Service
public final class CalculationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(CalculationWorker.class);
    private static final double CONTEXT_MARGIN_M = 22.0;
    private final JobRepository repository;
    private final FeatureIndex featureIndex;
    private final CrsTransform crsTransform;
    private final ObjectMapper mapper;
    private final CalculationExportPublisher exportPublisher;
    private final Clock clock;
    private final int maximumAttachmentsPerTarget;
    private final int maximumCombinations;
    private final int maximumVisibilityVertices;
    private final int maximumResults;
    private final ExecutorService routeSearchExecutor;
    private final int routeSearchParallelism;
    private final SingleTargetRouter.SearchMode routeSearchMode;
    private final boolean groupFocusedRouting;
    private final CalculationProgressStore progressStore;

    public CalculationWorker(JobRepository repository, FeatureIndex featureIndex, CrsTransform crsTransform,
            ObjectMapper mapper, CalculationExportPublisher exportPublisher,
            @Value("${app.calculation.max-attachments-per-target:4}") int maximumAttachmentsPerTarget,
            @Value("${app.calculation.max-combinations:64}") int maximumCombinations,
            @Value("${app.calculation.max-visibility-vertices:300}") int maximumVisibilityVertices,
            @Value("${app.calculation.max-results:3}") int maximumResults) {
        this(repository, featureIndex, crsTransform, mapper, exportPublisher,
                maximumAttachmentsPerTarget, maximumCombinations, maximumVisibilityVertices,
                maximumResults, new CalculationProgressStore(crsTransform), Clock.systemUTC(), null, 1,
                SingleTargetRouter.SearchMode.VISIBILITY, false);
    }

    @Autowired
    public CalculationWorker(JobRepository repository, FeatureIndex featureIndex, CrsTransform crsTransform,
            ObjectMapper mapper, CalculationExportPublisher exportPublisher,
            @Value("${app.calculation.max-attachments-per-target:4}") int maximumAttachmentsPerTarget,
            @Value("${app.calculation.max-combinations:64}") int maximumCombinations,
            @Value("${app.calculation.max-visibility-vertices:300}") int maximumVisibilityVertices,
            @Value("${app.calculation.max-results:3}") int maximumResults,
            @Qualifier("routeSearchExecutor") ExecutorService routeSearchExecutor,
            @Value("${app.calculation.route-search-parallelism:4}") int routeSearchParallelism,
            @Value("${app.calculation.routing-mode:visibility}") String routingMode,
            CalculationProgressStore progressStore) {
        this(repository, featureIndex, crsTransform, mapper, exportPublisher,
                maximumAttachmentsPerTarget, maximumCombinations, maximumVisibilityVertices,
                maximumResults, progressStore, Clock.systemUTC(), routeSearchExecutor,
                routeSearchParallelism, parseSearchMode(routingMode),
                "conservative-group-trunks".equalsIgnoreCase(routingMode.trim()));
    }

    CalculationWorker(JobRepository repository, FeatureIndex featureIndex, CrsTransform crsTransform,
            ObjectMapper mapper, CalculationExportPublisher exportPublisher,
            int maximumAttachmentsPerTarget, int maximumCombinations,
            int maximumVisibilityVertices, int maximumResults, Clock clock) {
        this(repository, featureIndex, crsTransform, mapper, exportPublisher,
                maximumAttachmentsPerTarget, maximumCombinations, maximumVisibilityVertices,
                maximumResults, new CalculationProgressStore(crsTransform), clock, null, 1,
                SingleTargetRouter.SearchMode.VISIBILITY, false);
    }

    private CalculationWorker(JobRepository repository, FeatureIndex featureIndex, CrsTransform crsTransform,
            ObjectMapper mapper, CalculationExportPublisher exportPublisher,
            int maximumAttachmentsPerTarget, int maximumCombinations,
            int maximumVisibilityVertices, int maximumResults, CalculationProgressStore progressStore, Clock clock,
            ExecutorService routeSearchExecutor, int routeSearchParallelism,
            SingleTargetRouter.SearchMode routeSearchMode, boolean groupFocusedRouting) {
        if (maximumAttachmentsPerTarget < 1 || maximumCombinations < 1 || maximumVisibilityVertices < 2
                || maximumResults < 1 || maximumResults > 3
                || routeSearchParallelism < 1 || routeSearchParallelism > 8
                || (routeSearchParallelism > 1 && routeSearchExecutor == null)) {
            throw new IllegalArgumentException("Calculation search limits are invalid");
        }
        this.repository = repository;
        this.featureIndex = featureIndex;
        this.crsTransform = crsTransform;
        this.mapper = mapper;
        this.exportPublisher = java.util.Objects.requireNonNull(exportPublisher, "exportPublisher");
        this.maximumAttachmentsPerTarget = maximumAttachmentsPerTarget;
        this.maximumCombinations = maximumCombinations;
        this.maximumVisibilityVertices = maximumVisibilityVertices;
        this.maximumResults = maximumResults;
        this.clock = clock;
        this.routeSearchExecutor = routeSearchExecutor;
        this.routeSearchParallelism = routeSearchParallelism;
        this.routeSearchMode = java.util.Objects.requireNonNull(routeSearchMode, "routeSearchMode");
        this.groupFocusedRouting = groupFocusedRouting;
        this.progressStore = java.util.Objects.requireNonNull(progressStore, "progressStore");
    }

    private static SingleTargetRouter.SearchMode parseSearchMode(String value) {
        if (value == null || "visibility".equalsIgnoreCase(value.trim())) {
            return SingleTargetRouter.SearchMode.VISIBILITY;
        }
        if ("adaptive-quadtree".equalsIgnoreCase(value.trim())) {
            return SingleTargetRouter.SearchMode.ADAPTIVE_QUADTREE;
        }
        if ("conservative-forest".equalsIgnoreCase(value.trim())) {
            return SingleTargetRouter.SearchMode.CONSERVATIVE_FOREST;
        }
        if ("conservative-exhaustive".equalsIgnoreCase(value.trim())) {
            return SingleTargetRouter.SearchMode.CONSERVATIVE_EXHAUSTIVE;
        }
        if ("conservative-direct-branch".equalsIgnoreCase(value.trim())) {
            return SingleTargetRouter.SearchMode.CONSERVATIVE_DIRECT_BRANCH;
        }
        if ("conservative-spatial".equalsIgnoreCase(value.trim())) {
            return SingleTargetRouter.SearchMode.CONSERVATIVE_SPATIAL;
        }
        if ("conservative-tree-navigator".equalsIgnoreCase(value.trim())
                || "conservative-group-trunks".equalsIgnoreCase(value.trim())) {
            return SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR;
        }
        throw new IllegalArgumentException("app.calculation.routing-mode must be visibility, adaptive-quadtree, conservative-forest, conservative-exhaustive, conservative-direct-branch, conservative-spatial, conservative-tree-navigator or conservative-group-trunks");
    }

    public void run(UUID jobId, Envelope searchArea) {
        progressStore.begin(jobId);
        try {
            CalculationOutput output = calculate(jobId, searchArea);
            exportPublisher.publish(jobId, output.variants, output.inputs);
            CalculationProgressSnapshot progress = progressStore.snapshot(jobId);
            output.result.setAttemptEvents(progress.getAttemptEvents());
            output.result.setTruncatedAttemptEvents(progress.getTruncatedAttemptEvents());
            String json = mapper.writeValueAsString(output.result);
            if (!repository.markCalculationSucceeded(jobId, json, clock.instant())) {
                throw new IllegalStateException("Job left CALCULATING before its result was persisted");
            }
        } catch (Exception failure) {
            LOG.error("Calculation failed for job {}", jobId, failure);
            try {
                String code = failure instanceof GeoJsonExportTooLargeException
                        ? "EXPORT_TOO_LARGE" : "CALCULATION_FAILED";
                String message = failure instanceof GeoJsonExportTooLargeException
                        ? "The generated GeoJSON export exceeds the configured "
                                + ((GeoJsonExportTooLargeException) failure).getLimitBytes() + " byte limit"
                        : "The routing calculation failed; inspect the service log for details";
                if (!repository.markCalculationFailed(jobId, code, message, clock.instant())) {
                    LOG.error("Could not persist calculation failure for job {}", jobId);
                }
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
                LOG.error("Could not persist calculation failure for job {}", jobId, persistenceFailure);
            }
        } finally {
            progressStore.clear(jobId);
        }
    }

    private CalculationOutput calculate(UUID jobId, Envelope searchArea) {
        Geometry area = rectangle(searchArea);
        List<FeatureIndex.IndexedFeature> inputs = featureIndex.findCalculationContext(jobId, area, CONTEXT_MARGIN_M);
        Map<TypedId, FeatureIndex.IndexedFeature> uniqueInputs = new LinkedHashMap<>();
        for (FeatureIndex.IndexedFeature input : inputs) {
            if (input == null || input.getFeature() == null || input.getFeature().getId() == null
                    || input.getGeometry() == null || input.getGeometry().getSRID() != CrsTransform.PROJECTED_SRID) {
                throw new IllegalStateException("Spatial index returned an incomplete or non-projected feature");
            }
            FeatureIndex.IndexedFeature duplicate = uniqueInputs.put(input.getFeature().getId(), input);
            if (duplicate != null) throw new IllegalStateException("Spatial index returned duplicate typed feature IDs");
        }
        inputs = new ArrayList<>(uniqueInputs.values());
        List<FeatureIndex.IndexedFeature> targets = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : inputs) {
            if ("oks_connection_point".equals(input.getFeature().getObjectType())) targets.add(input);
        }
        targets.sort(Comparator.comparing(input -> input.getFeature().getId(), TypedIdOrder.INSTANCE));
        if (targets.isEmpty()) {
            return new CalculationOutput(response("NO_TARGETS", "The uploaded dataset contains no connection targets",
                    0, 0, false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()),
                    new ArrayList<>(), inputs);
        }

        List<ExistingHeatNetwork> networks = networks(inputs);
        List<ExistingHeatChamber> chambers = chambers(inputs);
        ExistingNetworkConnector connector = new ExistingNetworkConnector();
        List<List<RouteRequest>> requests = new ArrayList<>();
        List<FeatureIndex.IndexedFeature> treeGrowthTargets = new ArrayList<>();
        Set<TypedId> definitelyUnconnected = new LinkedHashSet<>();
        Set<TypedId> unsearched = new LinkedHashSet<>();
        Map<TypedId, List<String>> targetAttempts = new LinkedHashMap<>();
        int requestSerial = 0;
        for (FeatureIndex.IndexedFeature target : targets) {
            if (!(target.getGeometry() instanceof Point)) throw new IllegalStateException("Connection target is not a point");
            Point point = (Point) target.getGeometry();
            TypedId targetId = target.getFeature().getId();
            if (!searchArea.covers(point.getX(), point.getY())) {
                unsearched.add(targetId);
                targetAttempts.put(targetId, Collections.singletonList("OUTSIDE_SEARCH_AREA"));
                continue;
            }
            treeGrowthTargets.add(target);
            List<AttachmentCandidate> attachments = new ArrayList<>(connector.findCandidates(point, networks, chambers)
                    .getCandidates());
            boolean hasAnyAttachment = !attachments.isEmpty();
            attachments.removeIf(attachment -> !searchArea.covers(attachment.getEndpoint().getX(),
                    attachment.getEndpoint().getY()));
            attachments.sort(Comparator.comparingDouble((AttachmentCandidate value) -> point.distance(value.getEndpoint()))
                    .thenComparing(AttachmentCandidate::getNetworkId, TypedIdOrder.INSTANCE)
                    .thenComparingDouble(AttachmentCandidate::getMeasureM));
            List<RouteRequest> targetRequests = new ArrayList<>();
            for (AttachmentCandidate attachment : attachments.subList(0,
                    Math.min(maximumAttachmentsPerTarget, attachments.size()))) {
                targetRequests.add(new RouteRequest("calc:" + jobId.toString().substring(0, 8)
                        + ":" + requestSerial++, target, attachment, inputs, searchArea,
                        maximumVisibilityVertices));
            }
            if (targetRequests.isEmpty()) {
                if (hasAnyAttachment) {
                    unsearched.add(targetId);
                    targetAttempts.put(targetId, Collections.singletonList("ATTACHMENT_OUTSIDE_SEARCH_AREA"));
                } else {
                    if (routeSearchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR) {
                        unsearched.add(targetId);
                    } else {
                        definitelyUnconnected.add(targetId);
                    }
                    targetAttempts.put(targetId, Collections.singletonList("NO_ATTACHMENT_CANDIDATE"));
                }
            } else {
                requests.add(targetRequests);
            }
        }

        if (requests.isEmpty()) {
            progressStore.configureSearch(jobId, targets.size(), 0);
            Set<TypedId> missing = new LinkedHashSet<>(definitelyUnconnected);
            missing.addAll(unsearched);
            return new CalculationOutput(response("NO_TARGET_ROUTED", "No target has a searchable connection to an existing heat network",
                    targets.size(), 0, false, sortedIds(missing), sortedIds(unsearched),
            targetSearches(targets, targetAttempts), new ArrayList<>()), new ArrayList<>(), inputs);
        }
        int totalAttempts = 0;
        for (List<RouteRequest> alternatives : requests) totalAttempts += alternatives.size();
        progressStore.configureSearch(jobId, targets.size(), totalAttempts);
        if (routeSearchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR) {
            progressStore.phase(jobId, "SEED_SEARCH");
        }
        RouteProgressListener progressListener = new RouteProgressListener() {
                    @Override
                    public void onAttemptCompleted(RouteRequest request, RouteCandidate candidate) {
                        progressStore.recordSearchAttempt(jobId, request, candidate);
                    }

                    @Override
                    public void onCombinationStarted() {
                        progressStore.phase(jobId, "COMBINING");
                    }

                    @Override
                    public void onTreeGrowthStarted() {
                        progressStore.phase(jobId, "TREE_GROWTH");
                    }

                    @Override
                    public void onTreeTrajectoryUpdated(String trajectoryId,
                            ru.lct.heatnetwork.domain.Variant checkedTree) {
                        progressStore.recordTreeTrajectory(jobId, trajectoryId, checkedTree);
                    }

                    @Override
                    public void onTreeAttemptCompleted(TypedId targetId, String attemptId,
                            Point destination, RouteCandidate.Status status,
                            String reason, Double lengthM) {
                        progressStore.recordTreeAttempt(jobId, targetId, attemptId,
                                destination, status, reason, lengthM);
                    }
                };
        MultiTargetRouter router = new MultiTargetRouter(routeSearchMode, groupFocusedRouting);
        MultiTargetRouteResult search = routeSearchMode == SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR
                ? router.route(requests, inputs, maximumCombinations, routeSearchExecutor,
                        routeSearchParallelism, progressListener, treeGrowthTargets, searchArea,
                        maximumVisibilityVertices)
                : router.route(requests, inputs, maximumCombinations, routeSearchExecutor,
                        routeSearchParallelism, progressListener);
        LOG.info("Routing search for job {} finished with {}, {} valid candidates, {} combinations, {} unrouted targets",
                jobId, search.getStatus(), search.getCandidates().size(), search.getCombinationsEvaluated(),
                search.getUnroutedTargetIds().size());
        if (search.getStatus() == MultiTargetRouteResult.Status.INVALID_REQUEST) {
            throw new IllegalStateException("Router rejected the assembled calculation request: " + search.getDetail());
        }
        for (Map.Entry<TypedId, List<RouteCandidate.Status>> entry : search.getTargetAttempts().entrySet()) {
            List<String> statuses = new ArrayList<>();
            for (RouteCandidate.Status status : entry.getValue()) statuses.add(status.name());
            targetAttempts.put(entry.getKey(), statuses);
        }
        if (routeSearchMode != SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR
                || search.getCandidates().isEmpty()) {
            unsearched.addAll(search.getUnsearchedTargetIds());
        }
        if (search.getCandidates().isEmpty()) {
            Set<TypedId> confirmedUnconnected = new LinkedHashSet<>(definitelyUnconnected);
            confirmedUnconnected.addAll(search.getUnroutedTargetIds());
            confirmedUnconnected.addAll(unsearched);
            return new CalculationOutput(response(search.getStatus().name(), search.getDetail(), targets.size(),
                    search.getCombinationsEvaluated(), search.isCombinationLimitReached(),
                    sortedIds(confirmedUnconnected), sortedIds(unsearched),
                    targetSearches(targets, targetAttempts), new ArrayList<>()), new ArrayList<>(), inputs);
        }

        Set<TypedId> allTargetIds = new LinkedHashSet<>();
        for (FeatureIndex.IndexedFeature target : targets) allTargetIds.add(target.getFeature().getId());
        List<CostedVariant> priced = new ArrayList<>();
        Map<String, Map<TypedId, String>> reasonsByVariant = new LinkedHashMap<>();
        VariantCostCalculator calculator = new VariantCostCalculator();
        for (RouteTreeOptimizer.TreeCandidate candidate : search.getCandidates()) {
            Set<TypedId> candidateUnsearched = new LinkedHashSet<>(unsearched);
            Map<TypedId, String> candidateReasons = new LinkedHashMap<>();
            if (search.hasCandidateDiagnostics(candidate.getVariant().getId())) {
                candidateReasons.putAll(search.getUnsearchedReasonsForCandidate(candidate.getVariant().getId()));
                candidateUnsearched.addAll(candidateReasons.keySet());
            } else {
                candidateUnsearched.addAll(search.getUnsearchedTargetIds());
            }
            candidateUnsearched.removeAll(connectedTargetIds(candidate.getVariant()));
            candidateReasons.keySet().retainAll(candidateUnsearched);
            for (TypedId targetId : candidateUnsearched) {
                List<String> statuses = targetAttempts.get(targetId);
                candidateReasons.putIfAbsent(targetId, statuses == null || statuses.isEmpty()
                        ? "ROUTE_SEARCH_LIMIT" : statuses.get(0));
            }
            Set<TypedId> candidateUnconnected = new LinkedHashSet<>(allTargetIds);
            candidateUnconnected.removeAll(connectedTargetIds(candidate.getVariant()));
            candidateUnconnected.removeAll(candidateUnsearched);
            priced.add(calculator.calculate(candidate.getVariant(), candidate.getValidation(), inputs,
                    candidateUnconnected, candidateUnsearched));
            reasonsByVariant.put(candidate.getVariant().getId(), candidateReasons);
        }
        List<CostedVariant> selected = new VariantSelector().select(priced, maximumResults, 1.0);
        List<CalculationResultResponse.VariantSummary> summaries = new ArrayList<>();
        int rank = 1;
        for (CostedVariant candidate : selected) {
            CalculationResultResponse.VariantSummary variantSummary = summary(rank++, candidate);
            variantSummary.setUnsearchedTargetIds(candidate.getSummary().getUnsearchedTargetIds());
            List<CalculationResultResponse.TargetReason> reasons = new ArrayList<>();
            Map<TypedId, String> byTarget = reasonsByVariant.get(candidate.getVariant().getId());
            for (TypedId targetId : candidate.getSummary().getUnsearchedTargetIds()) {
                reasons.add(new CalculationResultResponse.TargetReason(targetId, byTarget.get(targetId)));
            }
            variantSummary.setUnsearchedReasons(reasons);
            summaries.add(variantSummary);
        }
        List<TypedId> primaryUnconnected = selected.isEmpty()
                ? new ArrayList<>() : selected.get(0).getSummary().getUnconnectedTargetIds();
        Set<TypedId> primaryUnsearched = selected.isEmpty()
                ? new LinkedHashSet<>() : new LinkedHashSet<>(selected.get(0).getSummary().getUnsearchedTargetIds());
        String status = primaryUnconnected.isEmpty() && primaryUnsearched.isEmpty()
                ? search.getStatus().name() : "PARTIAL";
        return new CalculationOutput(response(status, search.getDetail(), targets.size(), search.getCombinationsEvaluated(),
                search.isCombinationLimitReached(), sortedIds(primaryUnconnected), sortedIds(primaryUnsearched),
                targetSearches(targets, targetAttempts), summaries), selected, inputs);
    }

    private List<ExistingHeatNetwork> networks(List<FeatureIndex.IndexedFeature> inputs) {
        List<ExistingHeatNetwork> result = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : inputs) {
            if (!"heat_network".equals(input.getFeature().getObjectType())) continue;
            if (!(input.getGeometry() instanceof org.locationtech.jts.geom.LineString)) {
                throw new IllegalStateException("Indexed heat network is not a LineString");
            }
            result.add(new ExistingHeatNetwork(input.getFeature().getId(),
                    (org.locationtech.jts.geom.LineString) input.getGeometry(), integerProperty(input, "diameter")));
        }
        return result;
    }

    private List<ExistingHeatChamber> chambers(List<FeatureIndex.IndexedFeature> inputs) {
        List<ExistingHeatChamber> result = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : inputs) {
            if (!"heat_chamber".equals(input.getFeature().getObjectType())) continue;
            if (!(input.getGeometry() instanceof Point)) throw new IllegalStateException("Indexed chamber is not a point");
            result.add(new ExistingHeatChamber(input.getFeature().getId(), (Point) input.getGeometry()));
        }
        return result;
    }

    private static int integerProperty(FeatureIndex.IndexedFeature input, String name) {
        com.fasterxml.jackson.databind.JsonNode value = input.getFeature().getProperties().getExtra().get(name);
        if (value == null || !value.canConvertToInt()) throw new IllegalStateException("Feature "
                + input.getFeature().getId() + " requires integer " + name);
        return value.intValue();
    }

    private static CalculationResultResponse.VariantSummary summary(int rank, CostedVariant candidate) {
        VariantCostSummary value = candidate.getSummary();
        return new CalculationResultResponse.VariantSummary(rank, candidate.getVariant().getId(),
                value.getConstructionCost(), value.getChamberConstructionCost(), value.getExistingChamberTieInCount(),
                value.getExistingChamberTieInCost(), value.getUnconnectedPenalty(), value.getCalculatedCost(),
                value.getNewNetworkLengthM(), value.getScore(), connectedTargetIds(candidate),
                value.getUnconnectedTargetIds());
    }

    private static List<TypedId> connectedTargetIds(CostedVariant candidate) {
        return connectedTargetIds(candidate.getVariant());
    }

    private static List<TypedId> connectedTargetIds(Variant variant) {
        List<TypedId> result = new ArrayList<>();
        for (Variant.Node node : variant.getNodes()) {
            if ("oks_connection_point".equals(node.getType()) && node.getInputFeatureId() != null) {
                result.add(node.getInputFeatureId());
            }
        }
        result.sort(TypedIdOrder.INSTANCE);
        return result;
    }

    private static CalculationResultResponse response(String status, String detail, int totalTargets,
            int combinationsEvaluated, boolean combinationLimitReached, List<TypedId> unconnected,
            List<TypedId> unsearched,
            List<CalculationResultResponse.TargetSearch> targetSearches,
            List<CalculationResultResponse.VariantSummary> variants) {
        return new CalculationResultResponse(status, detail, totalTargets, combinationsEvaluated,
                combinationLimitReached, unconnected, unsearched, targetSearches, variants);
    }

    private static List<CalculationResultResponse.TargetSearch> targetSearches(
            List<FeatureIndex.IndexedFeature> targets, Map<TypedId, List<String>> attemptsByTarget) {
        List<CalculationResultResponse.TargetSearch> result = new ArrayList<>();
        for (FeatureIndex.IndexedFeature target : targets) {
            TypedId targetId = target.getFeature().getId();
            List<String> attempts = attemptsByTarget.get(targetId);
            result.add(new CalculationResultResponse.TargetSearch(targetId,
                    attempts == null ? new ArrayList<>() : attempts));
        }
        return result;
    }

    private static List<TypedId> sortedIds(Collection<TypedId> ids) {
        List<TypedId> result = new ArrayList<>(new LinkedHashSet<>(ids));
        result.sort(TypedIdOrder.INSTANCE);
        return result;
    }

    private static final class CalculationOutput {
        private final CalculationResultResponse result;
        private final List<CostedVariant> variants;
        private final List<FeatureIndex.IndexedFeature> inputs;

        private CalculationOutput(CalculationResultResponse result, List<CostedVariant> variants,
                List<FeatureIndex.IndexedFeature> inputs) {
            this.result = result;
            this.variants = new ArrayList<>(variants);
            this.inputs = new ArrayList<>(inputs);
        }
    }

    private static Geometry rectangle(Envelope envelope) {
        GeometryFactory factory = new GeometryFactory();
        Geometry geometry = factory.toGeometry(envelope);
        geometry.setSRID(CrsTransform.PROJECTED_SRID);
        return geometry;
    }
}
