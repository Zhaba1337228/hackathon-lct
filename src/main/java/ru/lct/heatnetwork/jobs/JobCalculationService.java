package ru.lct.heatnetwork.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.locationtech.jts.geom.Envelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import ru.lct.heatnetwork.api.CalculateRequest;
import ru.lct.heatnetwork.api.CalculationResultResponse;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.projection.CrsTransform;

@Service
public final class JobCalculationService {
    private final JobRepository repository;
    private final CalculationWorker worker;
    private final TaskExecutor executor;
    private final CrsTransform crsTransform;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public JobCalculationService(JobRepository repository, CalculationWorker worker,
            @Qualifier("calculationTaskExecutor") TaskExecutor calculationTaskExecutor,
            CrsTransform crsTransform, ObjectMapper mapper) {
        this(repository, worker, calculationTaskExecutor, crsTransform, mapper, Clock.systemUTC());
    }

    JobCalculationService(JobRepository repository, CalculationWorker worker, TaskExecutor executor,
            CrsTransform crsTransform, ObjectMapper mapper, Clock clock) {
        this.repository = repository;
        this.worker = worker;
        this.executor = executor;
        this.crsTransform = crsTransform;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Job start(UUID id, CalculateRequest request) {
        if (id == null || request == null) throw new JobCalculationException(
                JobCalculationException.Kind.INVALID_REQUEST, "A job ID and calculation request are required");
        Job job = repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        Envelope searchArea;
        String bboxJson;
        try {
            searchArea = request.toProjectedSearchArea(crsTransform);
            bboxJson = mapper.writeValueAsString(request.getBbox());
        } catch (IllegalArgumentException | IOException invalid) {
            throw new JobCalculationException(JobCalculationException.Kind.INVALID_REQUEST, invalid.getMessage());
        }
        if (job.getStatus() != Job.Status.READY) {
            throw new JobCalculationException(JobCalculationException.Kind.STATE_CONFLICT,
                    "Calculation can only start for a READY job");
        }
        if (!repository.markCalculating(id, bboxJson, clock.instant())) {
            throw new JobCalculationException(JobCalculationException.Kind.STATE_CONFLICT,
                    "The job state changed before calculation could start");
        }
        try {
            executor.execute(() -> worker.run(id, searchArea));
        } catch (RuntimeException rejected) {
            repository.markCalculationFailed(id, "CALCULATION_QUEUE_FULL",
                    "The calculation queue is full; retry after another calculation finishes", clock.instant());
            throw new JobCalculationException(JobCalculationException.Kind.QUEUE_FULL,
                    "The calculation queue is full; retry after another calculation finishes");
        }
        return repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    public CalculationResultResponse result(UUID id) {
        Job job = repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.getArtifactsPurgedAt() != null || job.getArtifactCleanupAttemptedAt() != null) {
            throw new JobCalculationException(JobCalculationException.Kind.RESULT_EXPIRED,
                    "The calculation result has expired");
        }
        if (job.getStatus() != Job.Status.SUCCEEDED) {
            throw new JobCalculationException(JobCalculationException.Kind.RESULT_UNAVAILABLE,
                    "The calculation result is not available until the job succeeds");
        }
        Optional<String> result = repository.findCalculationResult(id);
        if (!result.isPresent()) {
            Job latest = repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
            if (latest.getArtifactsPurgedAt() != null || latest.getArtifactCleanupAttemptedAt() != null) {
                throw new JobCalculationException(JobCalculationException.Kind.RESULT_EXPIRED,
                        "The calculation result has expired");
            }
            throw new JobCalculationException(JobCalculationException.Kind.RESULT_UNAVAILABLE,
                    "The calculation result is unavailable");
        }
        String json = result.get();
        try {
            return mapper.readValue(json, CalculationResultResponse.class);
        } catch (IOException invalid) {
            throw new IllegalStateException("Persisted calculation result is invalid JSON", invalid);
        }
    }
}
