package ru.lct.heatnetwork.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnetwork.api.JobStatusResponse;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.domain.Job.FailureStage;
import ru.lct.heatnetwork.domain.Job.Status;
import ru.lct.heatnetwork.input.FeatureIdRegistry;
import ru.lct.heatnetwork.input.FeatureIdRegistryFactory;
import ru.lct.heatnetwork.input.GeoJsonStreamReader;
import ru.lct.heatnetwork.input.InputValidator;
import ru.lct.heatnetwork.input.StreamingUploadService;
import ru.lct.heatnetwork.input.UploadResult;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.spatial.FeatureIndex.IndexedFeature;

class JobArtifactRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    @TempDir
    Path storageRoot;

    @Test
    void purgesOnlyExpiredTerminalArtifactsAndKeepsJobMetadata() throws Exception {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        UUID failedId = addJob(repository, Status.FAILED, NOW.minus(Duration.ofHours(30)));
        UUID expiredSuccessId = addJob(repository, Status.SUCCEEDED, NOW.minus(Duration.ofHours(25)));
        UUID recentSuccessId = addJob(repository, Status.SUCCEEDED, NOW.minus(Duration.ofHours(23)));
        UUID readyId = addJob(repository, Status.READY, NOW.minus(Duration.ofHours(48)));
        Path failedFile = addArtifact(failedId);
        Path expiredSuccessFile = addArtifact(expiredSuccessId);
        Path recentFile = addArtifact(recentSuccessId);
        Path readyFile = addArtifact(readyId);
        JobArtifactRetentionService service = service(repository, new NoopFeatureIndex(), NOW);

        service.purgeExpiredArtifacts();

        assertFalse(Files.exists(failedFile));
        assertFalse(Files.exists(expiredSuccessFile));
        assertTrue(Files.exists(recentFile));
        assertTrue(Files.exists(readyFile));
        assertEquals(Status.FAILED, repository.findById(failedId).get().getStatus());
        Job purgedSuccess = repository.findById(expiredSuccessId).get();
        assertEquals(Status.SUCCEEDED, purgedSuccess.getStatus());
        assertNotNull(purgedSuccess.getArtifactsPurgedAt());
        assertFalse(JobStatusResponse.from(purgedSuccess).isResultAvailable());
        assertNull(repository.findById(recentSuccessId).get().getArtifactsPurgedAt());
    }

    @Test
    void retriesFailedCleanupAfterCooldownWithoutMarkingArtifactsPurged() throws Exception {
        InMemoryJobRepository repository = new InMemoryJobRepository();
        UUID failedId = addJob(repository, Status.FAILED, NOW.minus(Duration.ofHours(30)));
        Path file = addArtifact(failedId);
        AtomicInteger deleteCalls = new AtomicInteger();
        FeatureIndex failsOnce = new FeatureIndex() {
            @Override public void store(UUID jobId, InputFeature feature) { }
            @Override public List<IndexedFeature> findCandidates(
                    UUID jobId, org.locationtech.jts.geom.Geometry corridor) { return List.of(); }
            @Override public void deleteJob(UUID jobId) {
                if (deleteCalls.incrementAndGet() == 1) throw new IllegalStateException("simulated database failure");
            }
        };

        service(repository, failsOnce, NOW).purgeExpiredArtifacts();
        assertFalse(Files.exists(file));
        assertNull(repository.findById(failedId).get().getArtifactsPurgedAt());
        assertEquals(1, deleteCalls.get());

        service(repository, failsOnce, NOW.plus(Duration.ofMinutes(4))).purgeExpiredArtifacts();
        assertEquals(1, deleteCalls.get(), "cleanup should observe the retry cooldown");

        Instant retryAt = NOW.plus(Duration.ofMinutes(6));
        service(repository, failsOnce, retryAt).purgeExpiredArtifacts();
        assertEquals(2, deleteCalls.get());
        assertNotNull(repository.findById(failedId).get().getArtifactsPurgedAt());
    }

    private UUID addJob(InMemoryJobRepository repository, Status status, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        FailureStage failureStage = status == Status.FAILED ? FailureStage.VALIDATION : null;
        String errorCode = status == Status.FAILED ? "INVALID_GEOJSON" : null;
        repository.create(new Job(id, status, failureStage, updatedAt.minusSeconds(10), updatedAt,
                100, 1, Map.of("oks_connection_point", 1L), errorCode,
                errorCode == null ? null : "invalid test input", null, null));
        return id;
    }

    private Path addArtifact(UUID id) throws Exception {
        Path directory = Files.createDirectories(storageRoot.resolve(id.toString()));
        return Files.write(directory.resolve("input.geojson"), new byte[] {1});
    }

    private JobArtifactRetentionService service(
            InMemoryJobRepository repository, FeatureIndex index, Instant now) {
        GeoJsonStreamReader reader = new GeoJsonStreamReader(new InputValidator(), noOpRegistry());
        StreamingUploadService uploads = new StreamingUploadService(storageRoot, 1_000_000, reader, index);
        return new JobArtifactRetentionService(repository, uploads, Duration.ofHours(24),
                Duration.ofMinutes(5), 100, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static FeatureIdRegistryFactory noOpRegistry() {
        return () -> new FeatureIdRegistry() {
            @Override public void register(ru.lct.heatnetwork.domain.TypedId id) { }
            @Override public void complete() { }
            @Override public void close() { }
        };
    }

    private static final class InMemoryJobRepository implements JobRepository {
        private final Map<UUID, Job> jobs = new HashMap<>();
        private final Map<UUID, Instant> cleanupAttempts = new HashMap<>();

        @Override public void create(Job job) { jobs.put(job.getId(), job); }

        @Override
        public boolean markReady(UUID id, UploadResult upload, Instant updatedAt) {
            Job prior = jobs.get(id);
            if (prior == null || prior.getStatus() != Status.VALIDATING) return false;
            jobs.put(id, copy(prior, Status.READY, null, updatedAt, null,
                    upload.getReceivedBytes(), upload.getFeatureCount(), upload.getCountsByType(), null, null));
            return true;
        }

        @Override
        public boolean markValidationFailed(UUID id, String code, String message,
                String featureId, String fieldPath, Instant updatedAt) {
            Job prior = jobs.get(id);
            if (prior == null || prior.getStatus() != Status.VALIDATING) return false;
            jobs.put(id, copy(prior, Status.FAILED, FailureStage.VALIDATION, updatedAt, null,
                    prior.getReceivedBytes(), prior.getFeatureCount(), prior.getCountsByType(), code, message));
            return true;
        }

        @Override public Optional<Job> findById(UUID id) { return Optional.ofNullable(jobs.get(id)); }

        @Override public boolean markCalculating(UUID id, String bboxJson, Instant updatedAt) { return false; }
        @Override public boolean markCalculationSucceeded(UUID id, String resultJson, Instant updatedAt) { return false; }
        @Override public boolean markCalculationFailed(UUID id, String code, String message, Instant updatedAt) { return false; }
        @Override public int failInterruptedCalculations(String code, String message, Instant updatedAt) { return 0; }
        @Override public Optional<String> findCalculationResult(UUID id) { return Optional.empty(); }

        @Override
        public List<UUID> findExpiredArtifacts(Instant completedBefore, Instant retryBefore, int limit) {
            return jobs.values().stream()
                    .filter(job -> isTerminal(job.getStatus()))
                    .filter(job -> !job.getUpdatedAt().isAfter(completedBefore))
                    .filter(job -> job.getArtifactsPurgedAt() == null)
                    .filter(job -> cleanupAttempts.get(job.getId()) == null
                            || !cleanupAttempts.get(job.getId()).isAfter(retryBefore))
                    .sorted(Comparator.comparing(Job::getUpdatedAt).thenComparing(Job::getId))
                    .limit(limit)
                    .map(Job::getId)
                    .collect(Collectors.toList());
        }

        @Override
        public boolean claimArtifactCleanup(UUID id, Instant completedBefore, Instant retryBefore, Instant attemptedAt) {
            Job job = jobs.get(id);
            Instant priorAttempt = cleanupAttempts.get(id);
            if (job == null || !isTerminal(job.getStatus()) || job.getUpdatedAt().isAfter(completedBefore)
                    || job.getArtifactsPurgedAt() != null
                    || (priorAttempt != null && priorAttempt.isAfter(retryBefore))) return false;
            cleanupAttempts.put(id, attemptedAt);
            return true;
        }

        @Override
        public boolean markArtifactsPurged(UUID id, Instant completedBefore, Instant purgedAt) {
            Job prior = jobs.get(id);
            if (prior == null || !isTerminal(prior.getStatus()) || prior.getUpdatedAt().isAfter(completedBefore)
                    || prior.getArtifactsPurgedAt() != null) return false;
            jobs.put(id, copy(prior, prior.getStatus(), prior.getFailureStage(), prior.getUpdatedAt(), purgedAt,
                    prior.getReceivedBytes(), prior.getFeatureCount(), prior.getCountsByType(),
                    prior.getErrorCode(), prior.getErrorMessage()));
            return true;
        }

        private static Job copy(Job prior, Status status, FailureStage failureStage, Instant updatedAt,
                Instant artifactsPurgedAt, long receivedBytes, long featureCount,
                Map<String, Long> countsByType, String errorCode, String errorMessage) {
            return new Job(prior.getId(), status, failureStage, prior.getCreatedAt(), updatedAt,
                    artifactsPurgedAt, receivedBytes, featureCount, countsByType,
                    errorCode, errorMessage, prior.getErrorFeatureId(), prior.getErrorFieldPath());
        }

        private static boolean isTerminal(Status status) {
            return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED;
        }
    }

    private static final class NoopFeatureIndex implements FeatureIndex {
        @Override public void store(UUID jobId, InputFeature feature) { }
        @Override public List<IndexedFeature> findCandidates(
                UUID jobId, org.locationtech.jts.geom.Geometry corridor) { return new ArrayList<>(); }
        @Override public void deleteJob(UUID jobId) { }
    }
}
