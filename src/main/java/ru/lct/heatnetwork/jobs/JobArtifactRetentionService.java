package ru.lct.heatnetwork.jobs;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.lct.heatnetwork.input.StreamingUploadService;

/** Removes per-job files and indexed input after the configured retention period. */
@Service
public final class JobArtifactRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(JobArtifactRetentionService.class);

    private final JobRepository repository;
    private final StreamingUploadService uploads;
    private final Duration retention;
    private final Duration retryDelay;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    public JobArtifactRetentionService(
            JobRepository repository,
            StreamingUploadService uploads,
            @Value("${app.jobs.retention-hours:24}") long retentionHours,
            @Value("${app.jobs.cleanup-retry-minutes:5}") long retryMinutes,
            @Value("${app.jobs.cleanup-batch-size:100}") int batchSize) {
        this(repository, uploads, Duration.ofHours(retentionHours), Duration.ofMinutes(retryMinutes),
                batchSize, Clock.systemUTC());
    }

    JobArtifactRetentionService(JobRepository repository, StreamingUploadService uploads,
            Duration retention, Duration retryDelay, int batchSize, Clock clock) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.repository = repository;
        this.uploads = uploads;
        this.retention = retention;
        this.retryDelay = retryDelay;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.cleanup-interval-ms:60000}")
    public void purgeExpiredArtifacts() {
        Instant now = clock.instant();
        Instant completedBefore = now.minus(retention);
        Instant retryBefore = now.minus(retryDelay);
        List<UUID> expiredJobs = repository.findExpiredArtifacts(completedBefore, retryBefore, batchSize);

        for (UUID jobId : expiredJobs) {
            if (!repository.claimArtifactCleanup(jobId, completedBefore, retryBefore, now)) continue;
            try {
                uploads.discard(jobId);
                if (!repository.markArtifactsPurged(jobId, completedBefore, now)) {
                    LOG.warn("Cleaned artifacts for expired job {}, but could not persist the cleanup marker", jobId);
                }
            } catch (IOException | RuntimeException failure) {
                LOG.warn("Could not clean artifacts for expired job {}; retry is scheduled", jobId, failure);
            }
        }
    }
}
