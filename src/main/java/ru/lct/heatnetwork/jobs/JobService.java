package ru.lct.heatnetwork.jobs;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.input.InputValidationException;
import ru.lct.heatnetwork.input.PayloadTooLargeException;
import ru.lct.heatnetwork.input.StreamingUploadService;
import ru.lct.heatnetwork.input.UploadResult;

@Service
public final class JobService implements JobLookup {
    private final JobRepository repository;
    private final StreamingUploadService uploadService;
    private final Clock clock;

    @Autowired
    public JobService(JobRepository repository, StreamingUploadService uploadService) {
        this(repository, uploadService, Clock.systemUTC());
    }

    JobService(JobRepository repository, StreamingUploadService uploadService, Clock clock) {
        this.repository = repository;
        this.uploadService = uploadService;
        this.clock = clock;
    }

    public UploadResult upload(InputStream body) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        repository.create(Job.validating(id, now));
        try {
            UploadResult upload = uploadService.accept(id, body);
            if (!repository.markReady(id, upload, clock.instant())) {
                throw new IllegalStateException("Job left VALIDATING before its input was accepted");
            }
            return upload;
        } catch (IOException | RuntimeException failure) {
            persistFailure(id, failure);
            try {
                uploadService.discard(id);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new JobUploadException(id, failure);
        }
    }

    @Override
    public Job get(UUID id) {
        if (id == null) throw new IllegalArgumentException("job id is required");
        Optional<Job> job = repository.findById(id);
        return job.orElseThrow(() -> new JobNotFoundException(id));
    }

    private void persistFailure(UUID id, Throwable failure) {
        String code = "INPUT_PROCESSING_FAILED";
        String message = "The uploaded dataset could not be processed";
        String featureId = null;
        String fieldPath = null;
        if (failure instanceof InputValidationException) {
            InputValidationException invalid = (InputValidationException) failure;
            code = "INVALID_GEOJSON";
            message = invalid.getMessage();
            featureId = invalid.getFeatureId();
            fieldPath = invalid.getFieldPath();
        } else if (failure instanceof PayloadTooLargeException) {
            code = "UPLOAD_TOO_LARGE";
            message = failure.getMessage();
        }
        try {
            if (!repository.markValidationFailed(id, code, message, featureId, fieldPath, clock.instant())) {
                failure.addSuppressed(new IllegalStateException("Could not persist the validation failure state"));
            }
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }
}
