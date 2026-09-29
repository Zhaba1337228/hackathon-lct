package ru.lct.heatnetwork.jobs;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.input.UploadResult;

public interface JobRepository {
    void create(Job job);
    boolean markReady(UUID id, UploadResult upload, Instant updatedAt);
    boolean markValidationFailed(UUID id, String code, String message,
            String featureId, String fieldPath, Instant updatedAt);
    boolean markCalculating(UUID id, String bboxJson, Instant updatedAt);
    boolean markCalculationSucceeded(UUID id, String resultJson, Instant updatedAt);
    boolean markCalculationFailed(UUID id, String code, String message, Instant updatedAt);
    int failInterruptedCalculations(String code, String message, Instant updatedAt);
    Optional<String> findCalculationResult(UUID id);
    Optional<Job> findById(UUID id);
    List<UUID> findExpiredArtifacts(Instant completedBefore, Instant retryBefore, int limit);
    boolean claimArtifactCleanup(UUID id, Instant completedBefore, Instant retryBefore, Instant attemptedAt);
    boolean markArtifactsPurged(UUID id, Instant completedBefore, Instant purgedAt);
}
