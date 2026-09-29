package ru.lct.heatnetwork.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.domain.Job.FailureStage;
import ru.lct.heatnetwork.domain.Job.Status;
import ru.lct.heatnetwork.input.FeatureIdRegistry;
import ru.lct.heatnetwork.input.FeatureIdRegistryFactory;
import ru.lct.heatnetwork.input.GeoJsonStreamReader;
import ru.lct.heatnetwork.input.InputValidator;
import ru.lct.heatnetwork.input.StreamingUploadService;
import ru.lct.heatnetwork.input.UploadResult;
import ru.lct.heatnetwork.jobs.JobRepository;
import ru.lct.heatnetwork.jobs.JobCalculationService;
import ru.lct.heatnetwork.jobs.CalculationProgressStore;
import ru.lct.heatnetwork.jobs.CalculationWorker;
import ru.lct.heatnetwork.jobs.JobService;
import ru.lct.heatnetwork.output.GeoJsonJobExportService;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.spatial.FeatureIndex;

@WebMvcTest(controllers = {JobController.class, JobUploadController.class})
@Import({ApiExceptionHandler.class, JobService.class, JobApiControllerTest.ApiTestConfiguration.class})
class JobApiControllerTest {
    private static final String FIXTURE = "src/test/resources/fixtures/two-targets-one-building.geojson";
    private static final Path STORAGE_ROOT = Paths.get("target/job-api-controller-test-data").toAbsolutePath();
    private static final long MAX_UPLOAD_BYTES = 1_000_000L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestJobRepository repository;

    @Autowired
    private TestTaskExecutor taskExecutor;

    @Autowired
    private CalculationProgressStore progressStore;

    @AfterEach
    void clearTestState() throws IOException {
        repository.clear();
        taskExecutor.clear();
        if (Files.exists(STORAGE_ROOT)) {
            try (Stream<Path> paths = Files.walk(STORAGE_ROOT)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void statusRouteReturnsPersistedShapeAndDisablesCaching() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-25T10:00:00Z");
        Job ready = new Job(id, Status.READY, null, now, now, 512, 6,
                Map.of("oks_connection_point", 2L), null, null, null, null);
        repository.put(ready);

        mvc.perform(get("/api/jobs/{jobId}", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.jobId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.validationStatus").value("PASSED"))
                .andExpect(jsonPath("$.calculationStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.featureCount").value(6))
                .andExpect(jsonPath("$.countsByType.oks_connection_point").value(2))
                .andExpect(jsonPath("$.resultAvailable").value(false));
    }

    @Test
    void progressRouteReturnsNoStoreSnapshotAndUnknownJobsRemainNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-25T10:00:00Z");
        repository.put(new Job(id, Status.CALCULATING, null, now, now, 512, 6,
                Map.of("oks_connection_point", 2L), null, null, null, null));
        progressStore.begin(id);
        progressStore.configureSearch(id, 2, 4);

        mvc.perform(get("/api/jobs/{jobId}/progress", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.phase").value("ROUTE_SEARCH"))
                .andExpect(jsonPath("$.targetCount").value(2))
                .andExpect(jsonPath("$.completedAttempts").value(0))
                .andExpect(jsonPath("$.totalAttempts").value(4))
                .andExpect(jsonPath("$.previewRoutes").isArray());

        mvc.perform(get("/api/jobs/{jobId}/progress", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        progressStore.clear(id);
    }

    @Test
    void unknownJobMapsToNotFoundWithStableErrorCode() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/api/jobs/{jobId}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.jobId").value(id.toString()));
    }

    @Test
    void uploadRouteRunsRealParserAndReturnsAcceptedValidationSummary() throws Exception {
        byte[] fixture = Files.readAllBytes(Paths.get(FIXTURE));

        mvc.perform(post("/api/jobs").contentType("application/geo+json").content(fixture))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.stage").value("VALIDATED"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.validationStatus").value("PASSED"))
                .andExpect(jsonPath("$.calculationStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.featureCount").value(6));
    }

    @Test
    void validationFailureReturnsFeatureAndFieldAndPersistsFailedStatus() throws Exception {
        String invalid = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"bad-1\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}]}";

        String response = mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GEOJSON"))
                .andExpect(jsonPath("$.featureId").value("bad-1"))
                .andExpect(jsonPath("$.fieldPath").value("features[0].properties.object_type"))
                .andReturn().getResponse().getContentAsString();
        String failedId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("jobId").asText();

        mvc.perform(get("/api/jobs/{jobId}", failedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.validationStatus").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("INVALID_GEOJSON"))
                .andExpect(jsonPath("$.error.featureId").value("bad-1"));
    }

    @Test
    void oversizedUploadMapsTo413AndReturnsConfiguredLimit() throws Exception {
        byte[] oversized = new byte[(int) MAX_UPLOAD_BYTES + 1];

        mvc.perform(post("/api/jobs").contentType("application/geo+json").content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.limitBytes").value(MAX_UPLOAD_BYTES));
    }

    @Test
    void malformedUuidIsRejectedAndUnsupportedUploadMediaTypeIsNotAccepted() throws Exception {
        mvc.perform(get("/api/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/jobs").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void calculateAcceptsWgs84BboxAndPublishesOnlyAfterTheWorkerCompletes() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-25T10:00:00Z");
        repository.put(new Job(id, Status.READY, null, now, now, 10, 1, Map.of(), null, null, null, null));

        mvc.perform(post("/api/jobs/{jobId}/calculate", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bbox\":[37.5,55.6,37.7,55.8]}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("CALCULATING"))
                .andExpect(jsonPath("$.calculationStatus").value("RUNNING"));
        assertEquals(1, taskExecutor.size());

        mvc.perform(get("/api/jobs/{jobId}/variants", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_UNAVAILABLE"));
        mvc.perform(get("/api/jobs/{jobId}/export", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESULT_UNAVAILABLE"));

        taskExecutor.runAll();
        mvc.perform(get("/api/jobs/{jobId}/variants", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.searchStatus").value("NO_TARGETS"))
                .andExpect(jsonPath("$.totalTargets").value(0))
                .andExpect(jsonPath("$.unsearchedTargetIds.length()").value(0))
                .andExpect(jsonPath("$.targetSearches.length()").value(0))
                .andExpect(jsonPath("$.variants.length()").value(0));

        org.springframework.test.web.servlet.MvcResult export = mvc.perform(get("/api/jobs/{jobId}/export", id))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(export))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", "application/geo+json"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("heat-network-" + id)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{\"type\":\"FeatureCollection\",\"features\":[]}"));
    }

    @Test
    void exportMapsUnknownAndExpiredJobsToTheirApiStates() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-25T10:00:00Z");
        repository.put(new Job(id, Status.SUCCEEDED, null, now, now, now, 10, 1,
                Map.of(), null, null, null, null));

        mvc.perform(get("/api/jobs/{jobId}/export", id))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESULT_EXPIRED"));
        mvc.perform(get("/api/jobs/{jobId}/export", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void resultApisBecomeUnavailableAsSoonAsArtifactCleanupIsClaimed() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-25T10:00:00Z");
        repository.put(new Job(id, Status.SUCCEEDED, null, now, now, null, now, 10, 1,
                Map.of(), null, null, null, null));

        mvc.perform(get("/api/jobs/{jobId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultAvailable").value(false));
        mvc.perform(get("/api/jobs/{jobId}/variants", id))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESULT_EXPIRED"));
        mvc.perform(get("/api/jobs/{jobId}/export", id))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESULT_EXPIRED"));
    }

    @Test
    void invalidBboxIsRejectedBeforeJobStateChanges() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-25T10:00:00Z");
        repository.put(new Job(id, Status.READY, null, now, now, 10, 1, Map.of(), null, null, null, null));

        mvc.perform(post("/api/jobs/{jobId}/calculate", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bbox\":[37.7,55.8,37.5,55.6]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/jobs/{jobId}", id)).andExpect(jsonPath("$.status").value("READY"));
        assertEquals(0, taskExecutor.size());
    }

    @Test
    void duplicateCalculateCallReturnsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-25T10:00:00Z");
        repository.put(new Job(id, Status.READY, null, now, now, 10, 1, Map.of(), null, null, null, null));
        String body = "{\"bbox\":[37.5,55.6,37.7,55.8]}";

        mvc.perform(post("/api/jobs/{jobId}/calculate", id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/jobs/{jobId}/calculate", id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @TestConfiguration
    static class ApiTestConfiguration {
        @Bean
        TestJobRepository testJobRepository() {
            return new TestJobRepository();
        }

        @Bean
        StreamingUploadService streamingUploadService() {
            GeoJsonStreamReader reader = new GeoJsonStreamReader(new InputValidator(), noOpRegistry());
            return new StreamingUploadService(STORAGE_ROOT, MAX_UPLOAD_BYTES, reader);
        }

        @Bean
        FeatureIndex calculationFeatureIndex() { return noOpFeatureIndex(); }

        @Bean
        CrsTransform crsTransform() { return new CrsTransform(); }

        @Bean
        CalculationProgressStore calculationProgressStore(CrsTransform crsTransform) {
            return new CalculationProgressStore(crsTransform);
        }

        @Bean
        GeoJsonJobExportService geoJsonJobExportService(com.fasterxml.jackson.databind.ObjectMapper mapper,
                CrsTransform crsTransform, JobService jobs) {
            return new GeoJsonJobExportService(STORAGE_ROOT, 1_000_000, mapper, crsTransform, jobs);
        }

        @Bean
        CalculationWorker calculationWorker(TestJobRepository repository, FeatureIndex featureIndex,
                com.fasterxml.jackson.databind.ObjectMapper mapper, CrsTransform crsTransform,
                GeoJsonJobExportService exportService) {
            return new CalculationWorker(repository, featureIndex, crsTransform, mapper, exportService, 2, 4, 20, 3);
        }

        @Bean
        TestTaskExecutor testCalculationTaskExecutor() { return new TestTaskExecutor(); }

        @Bean
        JobCalculationService jobCalculationService(TestJobRepository repository, CalculationWorker worker,
                TestTaskExecutor executor, CrsTransform crsTransform,
                com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new JobCalculationService(repository, worker, executor, crsTransform, mapper);
        }
    }

    static final class TestJobRepository implements JobRepository {
        private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
        private final Map<UUID, String> results = new ConcurrentHashMap<>();

        @Override
        public void create(Job job) {
            jobs.put(job.getId(), job);
        }

        @Override
        public boolean markReady(UUID id, UploadResult upload, Instant updatedAt) {
            Job current = jobs.get(id);
            if (current == null || current.getStatus() != Status.VALIDATING) return false;
            jobs.put(id, new Job(id, Status.READY, null, current.getCreatedAt(), updatedAt,
                    upload.getReceivedBytes(), upload.getFeatureCount(), upload.getCountsByType(),
                    null, null, null, null));
            return true;
        }

        @Override
        public boolean markValidationFailed(UUID id, String code, String message,
                String featureId, String fieldPath, Instant updatedAt) {
            Job current = jobs.get(id);
            if (current == null || current.getStatus() != Status.VALIDATING) return false;
            jobs.put(id, new Job(id, Status.FAILED, FailureStage.VALIDATION,
                    current.getCreatedAt(), updatedAt, 0, 0, Map.of(), code, message, featureId, fieldPath));
            return true;
        }

        @Override
        public Optional<Job> findById(UUID id) {
            return Optional.ofNullable(jobs.get(id));
        }

        @Override
        public boolean markCalculating(UUID id, String bboxJson, Instant updatedAt) {
            java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
            jobs.compute(id, (key, current) -> {
                if (current == null || current.getStatus() != Status.READY) return current;
                changed.set(true);
                return new Job(id, Status.CALCULATING, null, current.getCreatedAt(), updatedAt,
                        current.getArtifactsPurgedAt(), current.getReceivedBytes(), current.getFeatureCount(),
                        current.getCountsByType(), null, null, null, null);
            });
            return changed.get();
        }

        @Override
        public boolean markCalculationSucceeded(UUID id, String resultJson, Instant updatedAt) {
            java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
            jobs.compute(id, (key, current) -> {
                if (current == null || current.getStatus() != Status.CALCULATING) return current;
                changed.set(true);
                return new Job(id, Status.SUCCEEDED, null, current.getCreatedAt(), updatedAt,
                        current.getArtifactsPurgedAt(), current.getReceivedBytes(), current.getFeatureCount(),
                        current.getCountsByType(), null, null, null, null);
            });
            if (changed.get()) results.put(id, resultJson);
            return changed.get();
        }

        @Override
        public boolean markCalculationFailed(UUID id, String code, String message, Instant updatedAt) {
            java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
            jobs.compute(id, (key, current) -> {
                if (current == null || current.getStatus() != Status.CALCULATING) return current;
                changed.set(true);
                return new Job(id, Status.FAILED, FailureStage.CALCULATION, current.getCreatedAt(), updatedAt,
                        current.getArtifactsPurgedAt(), current.getReceivedBytes(), current.getFeatureCount(),
                        current.getCountsByType(), code, message, null, null);
            });
            return changed.get();
        }

        @Override
        public int failInterruptedCalculations(String code, String message, Instant updatedAt) { return 0; }

        @Override public Optional<String> findCalculationResult(UUID id) { return Optional.ofNullable(results.get(id)); }

        @Override
        public List<UUID> findExpiredArtifacts(Instant completedBefore, Instant retryBefore, int limit) {
            return List.of();
        }

        @Override
        public boolean claimArtifactCleanup(UUID id, Instant completedBefore,
                Instant retryBefore, Instant attemptedAt) {
            return false;
        }

        @Override
        public boolean markArtifactsPurged(UUID id, Instant completedBefore, Instant purgedAt) {
            return false;
        }

        void put(Job job) {
            jobs.put(job.getId(), job);
        }

        void clear() {
            jobs.clear();
            results.clear();
        }
    }

    static final class TestTaskExecutor implements TaskExecutor {
        private final List<Runnable> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        int size() { return tasks.size(); }
        void runAll() {
            for (Runnable task : new java.util.ArrayList<>(tasks)) task.run();
            tasks.clear();
        }
        void clear() { tasks.clear(); }
    }

    private static FeatureIdRegistryFactory noOpRegistry() {
        return new FeatureIdRegistryFactory() {
            @Override public FeatureIdRegistry create() {
                return new FeatureIdRegistry() {
                    @Override public void register(ru.lct.heatnetwork.domain.TypedId id) { }
                    @Override public void complete() { }
                    @Override public void close() { }
                };
            }
        };
    }

    private static FeatureIndex noOpFeatureIndex() {
        return new FeatureIndex() {
            @Override public void store(UUID jobId, ru.lct.heatnetwork.domain.InputFeature feature) { }
            @Override public List<IndexedFeature> findCandidates(UUID jobId,
                    org.locationtech.jts.geom.Geometry corridor) { return List.of(); }
            @Override public void deleteJob(UUID jobId) { }
        };
    }
}
