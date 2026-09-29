package ru.lct.heatnetwork.jobs;

import java.util.UUID;

public final class JobUploadException extends RuntimeException {
    private final UUID jobId;

    public JobUploadException(UUID jobId, Throwable cause) {
        super("Job input validation failed", cause);
        this.jobId = jobId;
    }

    public UUID getJobId() { return jobId; }
}
