package ru.lct.heatnetwork.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnetwork.api.CalculationResultResponse;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.input.UploadResult;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.network.ExistingHeatNetwork;
import ru.lct.heatnetwork.output.GeoJsonExportTooLargeException;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.routing.ExistingNetworkConnector;
import ru.lct.heatnetwork.routing.RouteCandidate;
import ru.lct.heatnetwork.routing.RouteRequest;
import ru.lct.heatnetwork.routing.SingleTargetRouter;
import ru.lct.heatnetwork.spatial.FeatureIndex;

class CalculationWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final GeometryFactory PROJECTED_FACTORY = new GeometryFactory(new PrecisionModel(),
            CrsTransform.PROJECTED_SRID);

    @Test
    void calculatesAndPersistsAValidatedRankedVariant() throws Exception {
        UUID jobId = UUID.randomUUID();
        MemoryRepository repository = new MemoryRepository(calculatingJob(jobId));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        inputs.add(feature("target-1", "oks_connection_point", "POINT (500 100)",
                Collections.singletonMap("flow_tph", DoubleNode.valueOf(2.0))));
        Map<String, com.fasterxml.jackson.databind.JsonNode> networkProperties = new LinkedHashMap<>();
        networkProperties.put("diameter", com.fasterxml.jackson.databind.node.IntNode.valueOf(100));
        inputs.add(feature("network-1", "heat_network", "LINESTRING (0 0, 100 0)", networkProperties));

        FeatureIndex.IndexedFeature target = inputs.get(0);
        FeatureIndex.IndexedFeature network = inputs.get(1);
        List<AttachmentCandidate> attachmentCandidates = new ExistingNetworkConnector().findCandidates(
                (org.locationtech.jts.geom.Point) target.getGeometry(),
                Collections.singletonList(new ExistingHeatNetwork(network.getFeature().getId(),
                        (org.locationtech.jts.geom.LineString) network.getGeometry(), 100)),
                Collections.emptyList()).getCandidates();
        RouteCandidate route = new SingleTargetRouter().route(new RouteRequest("probe", target,
                attachmentCandidates.get(0), inputs, new Envelope(-1000, 1000, -1000, 1000), 40));
        assertTrue(route.isFound(), () -> route.getStatus() + ": " + route.getDetail()
                + (route.getValidation() == null ? "" : " issues=" + route.getValidation().getIssues().stream()
                        .map(issue -> issue.getCode() + ":" + issue.getElementId() + ":" + issue.getMessage()).collect(java.util.stream.Collectors.toList())
                        + " notRun=" + route.getValidation().getChecksNotRun()));

        CalculationProgressStore progressStore = new CalculationProgressStore(new CrsTransform());
        AtomicReference<Job.Status> stateAtPublish = new AtomicReference<>();
        CalculationWorker worker = worker(repository, inputs, (id, variants, source) -> {
            stateAtPublish.set(repository.findById(id).get().getStatus());
            CalculationProgressSnapshot progress = progressStore.snapshot(id);
            assertTrue(progress.isAvailable(), "progress remains visible through artifact publication");
            assertEquals(1, progress.getCompletedAttempts());
            assertEquals(1, progress.getTotalAttempts());
            assertEquals(1, progress.getPreviewRoutes().size());
            assertEquals(TypedId.of("target-1"), progress.getPreviewRoutes().get(0).getTargetId());
            assertEquals(1, variants.size());
            assertEquals(2, source.size());
        }, progressStore);
        worker.run(jobId, new Envelope(-1000, 1000, -1000, 1000));

        assertEquals(Job.Status.CALCULATING, stateAtPublish.get(), "artifact publication precedes SUCCEEDED");
        assertEquals(Job.Status.SUCCEEDED, repository.findById(jobId).get().getStatus());
        assertFalse(progressStore.snapshot(jobId).isAvailable(), "successful jobs discard transient progress");
        CalculationResultResponse result = new ObjectMapper().readValue(
                repository.findCalculationResult(jobId).get(), CalculationResultResponse.class);
        assertEquals("COMPLETE", result.getSearchStatus());
        assertEquals(1, result.getTotalTargets());
        assertTrue(result.getUnconnectedTargetIds().isEmpty());
        assertTrue(result.getUnsearchedTargetIds().isEmpty());
        assertEquals(TypedId.of("target-1"), result.getTargetSearches().get(0).getTargetId());
        assertEquals(Collections.singletonList("FOUND"), result.getTargetSearches().get(0).getAttempts());
        assertEquals(1, result.getAttemptEvents().size(), "completed search history is persisted");
        assertEquals("ROUTE_SEARCH", result.getAttemptEvents().get(0).getStage());
        assertEquals(TypedId.of("target-1"), result.getAttemptEvents().get(0).getTargetId());
        assertEquals("FOUND", result.getAttemptEvents().get(0).getStatus());
        assertEquals(0, result.getTruncatedAttemptEvents());
        assertEquals(1, result.getVariants().size());
        assertEquals(Collections.singletonList(TypedId.of("target-1")),
                result.getVariants().get(0).getConnectedTargetIds());
        assertTrue(result.getVariants().get(0).getCalculatedCost().signum() > 0);
        assertTrue(result.getVariants().get(0).getNewNetworkLengthM().doubleValue() > 0);
    }

    @Test
    void reportsTargetsOutsideSearchAreaAsUnsearchedAndMissing() throws Exception {
        UUID jobId = UUID.randomUUID();
        MemoryRepository repository = new MemoryRepository(calculatingJob(jobId));
        List<FeatureIndex.IndexedFeature> inputs = Collections.singletonList(
                feature("target-1", "oks_connection_point", "POINT (500 100)",
                        Collections.singletonMap("flow_tph", DoubleNode.valueOf(2.0))));

        worker(repository, inputs).run(jobId, new Envelope(-100, 100, -100, 100));

        assertEquals(Job.Status.SUCCEEDED, repository.findById(jobId).get().getStatus());
        CalculationResultResponse result = new ObjectMapper().readValue(
                repository.findCalculationResult(jobId).get(), CalculationResultResponse.class);
        assertEquals("NO_TARGET_ROUTED", result.getSearchStatus());
        assertEquals(Collections.singletonList(TypedId.of("target-1")), result.getUnconnectedTargetIds());
        assertEquals(Collections.singletonList(TypedId.of("target-1")), result.getUnsearchedTargetIds());
        assertEquals(Collections.singletonList("OUTSIDE_SEARCH_AREA"),
                result.getTargetSearches().get(0).getAttempts());
        assertTrue(result.getVariants().isEmpty());
    }

    @Test
    void keepsVertexLimitedTargetUnsearchedAndChargesMissingPenalty() throws Exception {
        UUID jobId = UUID.randomUUID();
        MemoryRepository repository = new MemoryRepository(calculatingJob(jobId));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        inputs.add(feature("target-good", "oks_connection_point", "POINT (120 0)",
                Collections.singletonMap("flow_tph", DoubleNode.valueOf(2.0))));
        inputs.add(feature("target-limited", "oks_connection_point", "POINT (50 50)",
                Collections.singletonMap("flow_tph", DoubleNode.valueOf(3.0))));
        inputs.add(feature("network-1", "heat_network", "LINESTRING (0 0, 100 0)",
                Collections.singletonMap("diameter", com.fasterxml.jackson.databind.node.IntNode.valueOf(100))));
        inputs.add(feature("wall", "restriction",
                "POLYGON ((-100 20, 200 20, 200 30, -100 30, -100 20))",
                Collections.singletonMap("restriction_type", new ObjectMapper().valueToTree("park"))));
        CalculationWorker worker = new CalculationWorker(repository, new FixtureIndex(inputs), new CrsTransform(),
                new ObjectMapper(), (id, variants, source) -> { }, 4, 64, 2, 3, ClockHolder.clock());

        worker.run(jobId, new Envelope(-100, 200, -100, 100));

        CalculationResultResponse result = new ObjectMapper().readValue(
                repository.findCalculationResult(jobId).get(), CalculationResultResponse.class);
        assertEquals("PARTIAL", result.getSearchStatus());
        assertEquals(Collections.singletonList(TypedId.of("target-limited")), result.getUnconnectedTargetIds());
        assertEquals(Collections.singletonList(TypedId.of("target-limited")), result.getUnsearchedTargetIds());
        assertTrue(result.getTargetSearches().stream()
                .filter(search -> search.getTargetId().equals(TypedId.of("target-limited")))
                .findFirst().get().getAttempts().contains("VISIBILITY_VERTEX_LIMIT_REACHED"));
        assertEquals(1, result.getVariants().size());
        assertEquals(Collections.singletonList(TypedId.of("target-good")),
                result.getVariants().get(0).getConnectedTargetIds());
        assertEquals(result.getUnsearchedTargetIds(), result.getVariants().get(0).getUnconnectedTargetIds());
        assertEquals(result.getUnsearchedTargetIds(), result.getVariants().get(0).getUnsearchedTargetIds());
        assertEquals(TypedId.of("target-limited"),
                result.getVariants().get(0).getUnsearchedReasons().get(0).getTargetId());
        assertEquals(0, new java.math.BigDecimal("101500000.00")
                .compareTo(result.getVariants().get(0).getUnconnectedPenalty()));
    }

    @Test
    void preservesRoutableTargetsAndChargesOutOfAreaTargetsInVariantPenalty() throws Exception {
        UUID jobId = UUID.randomUUID();
        MemoryRepository repository = new MemoryRepository(calculatingJob(jobId));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        inputs.add(feature("target-inside", "oks_connection_point", "POINT (500 100)",
                Collections.singletonMap("flow_tph", DoubleNode.valueOf(2.0))));
        inputs.add(feature("target-outside", "oks_connection_point", "POINT (1500 100)",
                Collections.singletonMap("flow_tph", DoubleNode.valueOf(3.0))));
        Map<String, com.fasterxml.jackson.databind.JsonNode> networkProperties = new LinkedHashMap<>();
        networkProperties.put("diameter", com.fasterxml.jackson.databind.node.IntNode.valueOf(100));
        inputs.add(feature("network-1", "heat_network", "LINESTRING (0 0, 100 0)", networkProperties));

        worker(repository, inputs).run(jobId, new Envelope(-1000, 1000, -1000, 1000));

        CalculationResultResponse result = new ObjectMapper().readValue(
                repository.findCalculationResult(jobId).get(), CalculationResultResponse.class);
        assertEquals("PARTIAL", result.getSearchStatus());
        assertEquals(Collections.singletonList(TypedId.of("target-outside")), result.getUnsearchedTargetIds());
        assertEquals(Collections.singletonList(TypedId.of("target-outside")), result.getUnconnectedTargetIds());
        assertEquals(1, result.getVariants().size());
        assertEquals(Collections.singletonList(TypedId.of("target-inside")),
                result.getVariants().get(0).getConnectedTargetIds());
        assertEquals(result.getUnconnectedTargetIds(), result.getVariants().get(0).getUnconnectedTargetIds());
        assertEquals(0, new java.math.BigDecimal("101500000.00")
                .compareTo(result.getVariants().get(0).getUnconnectedPenalty()));
    }

    @Test
    void turnsUnexpectedCalculationErrorsIntoPersistedCalculationFailure() {
        UUID jobId = UUID.randomUUID();
        MemoryRepository repository = new MemoryRepository(calculatingJob(jobId));
        CalculationProgressStore progressStore = new CalculationProgressStore(new CrsTransform());
        FeatureIndex brokenIndex = new FixtureIndex(Collections.emptyList()) {
            @Override public List<IndexedFeature> findCalculationContext(UUID id, Geometry area, double margin) {
                assertTrue(progressStore.snapshot(id).isAvailable(), "worker starts progress before reading inputs");
                throw new IllegalStateException("test database failure");
            }
        };
        CalculationWorker worker = worker(repository, brokenIndex, progressStore);

        worker.run(jobId, new Envelope(-1000, 1000, -1000, 1000));

        Job failed = repository.findById(jobId).get();
        assertEquals(Job.Status.FAILED, failed.getStatus());
        assertEquals(Job.FailureStage.CALCULATION, failed.getFailureStage());
        assertEquals("CALCULATION_FAILED", failed.getErrorCode());
        assertFalse(repository.findCalculationResult(jobId).isPresent());
        assertFalse(progressStore.snapshot(jobId).isAvailable(), "failed jobs discard transient progress");
    }

    @Test
    void exportFailureCannotPublishCalculationAsSucceeded() throws Exception {
        UUID jobId = UUID.randomUUID();
        MemoryRepository repository = new MemoryRepository(calculatingJob(jobId));
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        inputs.add(feature("target-1", "oks_connection_point", "POINT (500 100)",
                Collections.singletonMap("flow_tph", DoubleNode.valueOf(2.0))));
        Map<String, com.fasterxml.jackson.databind.JsonNode> networkProperties = new LinkedHashMap<>();
        networkProperties.put("diameter", com.fasterxml.jackson.databind.node.IntNode.valueOf(100));
        inputs.add(feature("network-1", "heat_network", "LINESTRING (0 0, 100 0)", networkProperties));

        CalculationWorker worker = worker(repository, inputs,
                (id, variants, source) -> { throw new GeoJsonExportTooLargeException(8); });
        worker.run(jobId, new Envelope(-1000, 1000, -1000, 1000));

        Job failed = repository.findById(jobId).get();
        assertEquals(Job.Status.FAILED, failed.getStatus());
        assertEquals(Job.FailureStage.CALCULATION, failed.getFailureStage());
        assertEquals("EXPORT_TOO_LARGE", failed.getErrorCode());
        assertTrue(failed.getErrorMessage().contains("8 byte limit"));
        assertFalse(repository.findCalculationResult(jobId).isPresent());
    }

    private static CalculationWorker worker(MemoryRepository repository,
            List<FeatureIndex.IndexedFeature> inputs) {
        return worker(repository, inputs, (id, variants, source) -> { });
    }

    private static CalculationWorker worker(MemoryRepository repository,
            List<FeatureIndex.IndexedFeature> inputs, CalculationExportPublisher publisher) {
        return new CalculationWorker(repository, new FixtureIndex(inputs), new CrsTransform(),
                new ObjectMapper(), publisher, 2, 4, 40, 3, ClockHolder.clock());
    }

    private static CalculationWorker worker(MemoryRepository repository,
            List<FeatureIndex.IndexedFeature> inputs, CalculationExportPublisher publisher,
            CalculationProgressStore progressStore) {
        return new CalculationWorker(repository, new FixtureIndex(inputs), new CrsTransform(),
                new ObjectMapper(), publisher, 2, 4, 40, 3, null, 1, "visibility", progressStore);
    }

    private static CalculationWorker worker(MemoryRepository repository, FeatureIndex featureIndex,
            CalculationProgressStore progressStore) {
        return new CalculationWorker(repository, featureIndex, new CrsTransform(), new ObjectMapper(),
                (id, variants, source) -> { }, 2, 4, 20, 3, null, 1, "visibility", progressStore);
    }

    private static FeatureIndex.IndexedFeature feature(String id, String type, String wkt,
            Map<String, com.fasterxml.jackson.databind.JsonNode> extra) throws Exception {
        FeatureProperties properties = new FeatureProperties(TypedId.of(id), type);
        for (Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry : extra.entrySet()) {
            properties.putExtra(entry.getKey(), entry.getValue());
        }
        InputFeature feature = new InputFeature(properties, com.fasterxml.jackson.databind.node.NullNode.getInstance());
        Geometry geometry = new WKTReader(PROJECTED_FACTORY).read(wkt);
        return new FeatureIndex.IndexedFeature(feature, geometry);
    }

    private static Job calculatingJob(UUID id) {
        return new Job(id, Job.Status.CALCULATING, null, NOW, NOW, 10, 2, Map.of(), null, null, null, null);
    }

    private static class FixtureIndex implements FeatureIndex {
        private final List<IndexedFeature> inputs;
        private FixtureIndex(List<IndexedFeature> inputs) { this.inputs = inputs; }
        @Override public void store(UUID jobId, InputFeature feature) { }
        @Override public List<IndexedFeature> findCandidates(UUID jobId, Geometry corridor) { return List.of(); }
        @Override public List<IndexedFeature> findCalculationContext(UUID jobId, Geometry searchArea, double marginM) {
            return new ArrayList<>(inputs);
        }
        @Override public void deleteJob(UUID jobId) { }
    }

    private static final class MemoryRepository implements JobRepository {
        private final Map<UUID, Job> jobs = new LinkedHashMap<>();
        private final Map<UUID, String> results = new LinkedHashMap<>();
        private MemoryRepository(Job job) { jobs.put(job.getId(), job); }
        @Override public void create(Job job) { jobs.put(job.getId(), job); }
        @Override public boolean markReady(UUID id, UploadResult upload, Instant updatedAt) { return false; }
        @Override public boolean markValidationFailed(UUID id, String code, String message,
                String featureId, String fieldPath, Instant updatedAt) { return false; }
        @Override public boolean markCalculating(UUID id, String bboxJson, Instant updatedAt) { return false; }
        @Override public boolean markCalculationSucceeded(UUID id, String resultJson, Instant updatedAt) {
            Job prior = jobs.get(id);
            if (prior == null || prior.getStatus() != Job.Status.CALCULATING) return false;
            jobs.put(id, new Job(id, Job.Status.SUCCEEDED, null, prior.getCreatedAt(), updatedAt,
                    prior.getReceivedBytes(), prior.getFeatureCount(), prior.getCountsByType(),
                    null, null, null, null));
            results.put(id, resultJson);
            return true;
        }
        @Override public boolean markCalculationFailed(UUID id, String code, String message, Instant updatedAt) {
            Job prior = jobs.get(id);
            if (prior == null || prior.getStatus() != Job.Status.CALCULATING) return false;
            jobs.put(id, new Job(id, Job.Status.FAILED, Job.FailureStage.CALCULATION,
                    prior.getCreatedAt(), updatedAt, prior.getReceivedBytes(), prior.getFeatureCount(),
                    prior.getCountsByType(), code, message, null, null));
            return true;
        }
        @Override public int failInterruptedCalculations(String code, String message, Instant updatedAt) { return 0; }
        @Override public Optional<String> findCalculationResult(UUID id) { return Optional.ofNullable(results.get(id)); }
        @Override public Optional<Job> findById(UUID id) { return Optional.ofNullable(jobs.get(id)); }
        @Override public List<UUID> findExpiredArtifacts(Instant completedBefore, Instant retryBefore, int limit) { return List.of(); }
        @Override public boolean claimArtifactCleanup(UUID id, Instant completedBefore, Instant retryBefore, Instant attemptedAt) { return false; }
        @Override public boolean markArtifactsPurged(UUID id, Instant completedBefore, Instant purgedAt) { return false; }
    }

    private static final class ClockHolder {
        private static java.time.Clock clock() { return java.time.Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
