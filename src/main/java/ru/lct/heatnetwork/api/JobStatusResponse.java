package ru.lct.heatnetwork.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ru.lct.heatnetwork.domain.Job;

@Schema(description = "Persisted processing state for one uploaded dataset")
public final class JobStatusResponse {
    public enum ValidationStatus { RUNNING, PASSED, FAILED }
    public enum CalculationStatus { NOT_STARTED, RUNNING, COMPLETED, FAILED }

    private final UUID jobId;
    private final Job.Status status;
    private final ValidationStatus validationStatus;
    private final CalculationStatus calculationStatus;
    private final long receivedBytes;
    private final long featureCount;
    private final Map<String, Long> countsByType;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final boolean resultAvailable;
    private final Error error;

    private JobStatusResponse(Job job) {
        this.jobId = job.getId();
        this.status = job.getStatus();
        this.validationStatus = validationStatus(job);
        this.calculationStatus = calculationStatus(job);
        this.receivedBytes = job.getReceivedBytes();
        this.featureCount = job.getFeatureCount();
        this.countsByType = Collections.unmodifiableMap(new LinkedHashMap<>(job.getCountsByType()));
        this.createdAt = job.getCreatedAt();
        this.updatedAt = job.getUpdatedAt();
        this.resultAvailable = job.getStatus() == Job.Status.SUCCEEDED && job.getArtifactsPurgedAt() == null
                && job.getArtifactCleanupAttemptedAt() == null;
        this.error = job.getErrorCode() == null ? null : new Error(job);
    }

    public static JobStatusResponse from(Job job) { return new JobStatusResponse(job); }

    private static ValidationStatus validationStatus(Job job) {
        if (job.getStatus() == Job.Status.VALIDATING) return ValidationStatus.RUNNING;
        if (job.getFailureStage() == Job.FailureStage.VALIDATION) return ValidationStatus.FAILED;
        return ValidationStatus.PASSED;
    }

    private static CalculationStatus calculationStatus(Job job) {
        if (job.getStatus() == Job.Status.CALCULATING) return CalculationStatus.RUNNING;
        if (job.getStatus() == Job.Status.SUCCEEDED) return CalculationStatus.COMPLETED;
        if (job.getFailureStage() == Job.FailureStage.CALCULATION) return CalculationStatus.FAILED;
        return CalculationStatus.NOT_STARTED;
    }

    public UUID getJobId() { return jobId; }
    public Job.Status getStatus() { return status; }
    public ValidationStatus getValidationStatus() { return validationStatus; }
    public CalculationStatus getCalculationStatus() { return calculationStatus; }
    public long getReceivedBytes() { return receivedBytes; }
    public long getFeatureCount() { return featureCount; }
    public Map<String, Long> getCountsByType() { return countsByType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isResultAvailable() { return resultAvailable; }
    public Error getError() { return error; }

    @Schema(description = "Stable error information for the failed validation or calculation")
    public static final class Error {
        private final Job.FailureStage stage;
        private final String code;
        private final String message;
        private final String featureId;
        private final String fieldPath;

        private Error(Job job) {
            this.stage = job.getFailureStage();
            this.code = job.getErrorCode();
            this.message = job.getErrorMessage();
            this.featureId = job.getErrorFeatureId();
            this.fieldPath = job.getErrorFieldPath();
        }

        public Job.FailureStage getStage() { return stage; }
        public String getCode() { return code; }
        public String getMessage() { return message; }
        public String getFeatureId() { return featureId; }
        public String getFieldPath() { return fieldPath; }
    }
}
