package ru.lct.heatnetwork.jobs;

import java.util.UUID;

public final class JobNotFoundException extends RuntimeException {
    private final UUID jobId;

    public JobNotFoundException(UUID jobId) {
        super("Job was not found");
        this.jobId = jobId;
    }

    public UUID getJobId() { return jobId; }
}
