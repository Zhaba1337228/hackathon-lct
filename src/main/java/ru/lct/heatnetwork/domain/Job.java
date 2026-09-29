package ru.lct.heatnetwork.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable processing state for one isolated input dataset. */
public final class Job {
    public enum Status { VALIDATING, READY, CALCULATING, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED }
    public enum FailureStage { VALIDATION, CALCULATION }

    private final UUID id;
    private final Status status;
    private final FailureStage failureStage;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant artifactsPurgedAt;
    private final Instant artifactCleanupAttemptedAt;
    private final long receivedBytes;
    private final long featureCount;
    private final Map<String, Long> countsByType;
    private final String errorCode;
    private final String errorMessage;
    private final String errorFeatureId;
    private final String errorFieldPath;

    public Job(UUID id, Status status, FailureStage failureStage, Instant createdAt, Instant updatedAt,
            long receivedBytes, long featureCount, Map<String, Long> countsByType,
            String errorCode, String errorMessage, String errorFeatureId, String errorFieldPath) {
        this(id, status, failureStage, createdAt, updatedAt, null, null, receivedBytes, featureCount,
                countsByType, errorCode, errorMessage, errorFeatureId, errorFieldPath);
    }

    public Job(UUID id, Status status, FailureStage failureStage, Instant createdAt, Instant updatedAt,
            Instant artifactsPurgedAt, long receivedBytes, long featureCount, Map<String, Long> countsByType,
            String errorCode, String errorMessage, String errorFeatureId, String errorFieldPath) {
        this(id, status, failureStage, createdAt, updatedAt, artifactsPurgedAt, null, receivedBytes,
                featureCount, countsByType, errorCode, errorMessage, errorFeatureId, errorFieldPath);
    }

    public Job(UUID id, Status status, FailureStage failureStage, Instant createdAt, Instant updatedAt,
            Instant artifactsPurgedAt, Instant artifactCleanupAttemptedAt, long receivedBytes,
            long featureCount, Map<String, Long> countsByType,
            String errorCode, String errorMessage, String errorFeatureId, String errorFieldPath) {
        this.id = Objects.requireNonNull(id, "id");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.artifactsPurgedAt = artifactsPurgedAt;
        this.artifactCleanupAttemptedAt = artifactCleanupAttemptedAt;
        if (receivedBytes < 0 || featureCount < 0) throw new IllegalArgumentException("Job counts cannot be negative");
        if (status == Status.FAILED && (errorCode == null || errorCode.trim().isEmpty())) {
            throw new IllegalArgumentException("A failed job must have an error code");
        }
        if (status == Status.FAILED && failureStage == null) {
            throw new IllegalArgumentException("A failed job must identify its failure stage");
        }
        this.failureStage = failureStage;
        this.receivedBytes = receivedBytes;
        this.featureCount = featureCount;
        this.countsByType = Collections.unmodifiableMap(new LinkedHashMap<>(
                countsByType == null ? Collections.emptyMap() : countsByType));
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.errorFeatureId = errorFeatureId;
        this.errorFieldPath = errorFieldPath;
    }

    public static Job validating(UUID id, Instant now) {
        return new Job(id, Status.VALIDATING, null, now, now, 0, 0,
                Collections.emptyMap(), null, null, null, null);
    }

    public UUID getId() { return id; }
    public Status getStatus() { return status; }
    public FailureStage getFailureStage() { return failureStage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getArtifactsPurgedAt() { return artifactsPurgedAt; }
    public Instant getArtifactCleanupAttemptedAt() { return artifactCleanupAttemptedAt; }
    public long getReceivedBytes() { return receivedBytes; }
    public long getFeatureCount() { return featureCount; }
    public Map<String, Long> getCountsByType() { return countsByType; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getErrorFeatureId() { return errorFeatureId; }
    public String getErrorFieldPath() { return errorFieldPath; }
}
