package ru.lct.heatnetwork.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnetwork.api.JobStatusResponse;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.input.DuplicateInputIdException;
import ru.lct.heatnetwork.input.FeatureIdRegistry;
import ru.lct.heatnetwork.input.FeatureIdRegistryFactory;
import ru.lct.heatnetwork.input.GeoJsonStreamReader;
import ru.lct.heatnetwork.input.InputValidator;
import ru.lct.heatnetwork.input.StreamingUploadService;
import ru.lct.heatnetwork.input.UploadResult;

class JobServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void persistsAReadyJobAndExposesIndependentValidationAndCalculationStates() throws Exception {
        InMemoryJobs jobs = new InMemoryJobs();
        JobService service = service(jobs, 1_000_000);
        byte[] fixture = Files.readAllBytes(Path.of("src", "test", "resources", "fixtures",
                "two-targets-one-building.geojson"));

        UploadResult first = service.upload(new ByteArrayInputStream(fixture));
        UploadResult second = service.upload(new ByteArrayInputStream(fixture));

        assertNotEquals(first.getJobId(), second.getJobId());
        assertEquals(2, jobs.rows.size());
        JobStatusResponse status = JobStatusResponse.from(service.get(first.getJobId()));
        assertEquals(Job.Status.READY, status.getStatus());
        assertEquals(JobStatusResponse.ValidationStatus.PASSED, status.getValidationStatus());
        assertEquals(JobStatusResponse.CalculationStatus.NOT_STARTED, status.getCalculationStatus());
        assertFalse(status.isResultAvailable());
        assertEquals(first.getFeatureCount(), status.getFeatureCount());
        assertTrue(Files.exists(tempDirectory.resolve(first.getJobId().toString()).resolve("input.geojson")));
        assertTrue(Files.exists(tempDirectory.resolve(second.getJobId().toString()).resolve("input.geojson")));
    }

    @Test
    void retainsAValidationFailureWithJobIdFeatureAndFieldAndCleansInputFiles() {
        InMemoryJobs jobs = new InMemoryJobs();
        JobService service = service(jobs, 1_000_000);
        byte[] invalid = ("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                + "\"properties\":{\"id\":\"bad-1\"},\"geometry\":{\"type\":\"Point\","
                + "\"coordinates\":[37.6,55.7]}}]}")
                .getBytes(StandardCharsets.UTF_8);

        JobUploadException failure = assertThrows(JobUploadException.class,
                () -> service.upload(new ByteArrayInputStream(invalid)));

        Job failed = service.get(failure.getJobId());
        JobStatusResponse status = JobStatusResponse.from(failed);
        assertEquals(Job.Status.FAILED, failed.getStatus());
        assertEquals(Job.FailureStage.VALIDATION, failed.getFailureStage());
        assertEquals(JobStatusResponse.ValidationStatus.FAILED, status.getValidationStatus());
        assertEquals(JobStatusResponse.CalculationStatus.NOT_STARTED, status.getCalculationStatus());
        assertTrue(status.getError() != null);
        assertEquals("bad-1", status.getError().getFeatureId());
        assertTrue(status.getError().getFieldPath().contains("object_type"));
        assertFalse(Files.exists(tempDirectory.resolve(failure.getJobId().toString())));
    }

    @Test
    void rejectsUnknownIdsWithoutReturningAnotherJobsState() {
        JobService service = service(new InMemoryJobs(), 1_000_000);
        UUID missing = UUID.randomUUID();

        JobNotFoundException failure = assertThrows(JobNotFoundException.class, () -> service.get(missing));

        assertEquals(missing, failure.getJobId());
    }

    private JobService service(InMemoryJobs jobs, long limit) {
        StreamingUploadService uploads = new StreamingUploadService(tempDirectory, limit,
                new GeoJsonStreamReader(new InputValidator(), new TestIdRegistryFactory()));
        Clock clock = Clock.fixed(Instant.parse("2026-09-25T09:00:00Z"), ZoneOffset.UTC);
        return new JobService(jobs, uploads, clock);
    }

    private static final class InMemoryJobs implements JobRepository {
        private final Map<UUID, Job> rows = new LinkedHashMap<>();

        @Override
        public void create(Job job) {
            if (rows.putIfAbsent(job.getId(), job) != null) throw new IllegalStateException("Duplicate job ID");
        }

        @Override
        public boolean markReady(UUID id, UploadResult upload, Instant updatedAt) {
            Job prior = rows.get(id);
            if (prior == null || prior.getStatus() != Job.Status.VALIDATING) return false;
            rows.put(id, new Job(id, Job.Status.READY, null, prior.getCreatedAt(), updatedAt,
                    upload.getReceivedBytes(), upload.getFeatureCount(), upload.getCountsByType(),
                    null, null, null, null));
            return true;
        }

        @Override
        public boolean markValidationFailed(UUID id, String code, String message,
                String featureId, String fieldPath, Instant updatedAt) {
            Job prior = rows.get(id);
            if (prior == null || prior.getStatus() != Job.Status.VALIDATING) return false;
            rows.put(id, new Job(id, Job.Status.FAILED, Job.FailureStage.VALIDATION,
                    prior.getCreatedAt(), updatedAt, 0, 0, prior.getCountsByType(),
                    code, message, featureId, fieldPath));
            return true;
        }

        @Override
        public Optional<Job> findById(UUID id) { return Optional.ofNullable(rows.get(id)); }

        @Override public boolean markCalculating(UUID id, String bboxJson, Instant updatedAt) { return false; }
        @Override public boolean markCalculationSucceeded(UUID id, String resultJson, Instant updatedAt) { return false; }
        @Override public boolean markCalculationFailed(UUID id, String code, String message, Instant updatedAt) { return false; }
        @Override public int failInterruptedCalculations(String code, String message, Instant updatedAt) { return 0; }
        @Override public Optional<String> findCalculationResult(UUID id) { return Optional.empty(); }

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
    }

    private static final class TestIdRegistryFactory implements FeatureIdRegistryFactory {
        @Override
        public FeatureIdRegistry create() {
            return new FeatureIdRegistry() {
                private final Set<TypedId> ids = new HashSet<>();
                @Override public void register(TypedId id) {
                    if (!ids.add(id)) throw new DuplicateInputIdException();
                }
                @Override public void complete() { }
                @Override public void close() { ids.clear(); }
            };
        }
    }
}
