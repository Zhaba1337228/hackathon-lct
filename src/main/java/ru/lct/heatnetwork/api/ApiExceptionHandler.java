package ru.lct.heatnetwork.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.lct.heatnetwork.jobs.JobNotFoundException;
import ru.lct.heatnetwork.jobs.JobCalculationException;
import ru.lct.heatnetwork.jobs.JobUploadException;
import ru.lct.heatnetwork.input.InputValidationException;
import ru.lct.heatnetwork.input.PayloadTooLargeException;

@RestControllerAdvice
public final class ApiExceptionHandler {
    @ExceptionHandler(InputValidationException.class)
    public ResponseEntity<Map<String, Object>> invalidInput(InputValidationException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_GEOJSON");
        body.put("message", exception.getMessage());
        body.put("feature_id", exception.getFeatureId());
        body.put("field_path", exception.getFieldPath());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(PayloadTooLargeException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "UPLOAD_TOO_LARGE");
        body.put("limit_bytes", exception.getLimitBytes());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(JobUploadException.class)
    public ResponseEntity<Map<String, Object>> jobUploadFailed(JobUploadException exception) {
        Throwable cause = exception.getCause();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", exception.getJobId());
        if (cause instanceof InputValidationException) {
            InputValidationException invalid = (InputValidationException) cause;
            body.put("code", "INVALID_GEOJSON");
            body.put("message", invalid.getMessage());
            body.put("featureId", invalid.getFeatureId());
            body.put("fieldPath", invalid.getFieldPath());
            return ResponseEntity.badRequest().body(body);
        }
        if (cause instanceof PayloadTooLargeException) {
            PayloadTooLargeException tooLarge = (PayloadTooLargeException) cause;
            body.put("code", "UPLOAD_TOO_LARGE");
            body.put("message", tooLarge.getMessage());
            body.put("limitBytes", tooLarge.getLimitBytes());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
        }
        body.put("code", "INPUT_PROCESSING_FAILED");
        body.put("message", "The uploaded dataset could not be processed");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> jobNotFound(JobNotFoundException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "JOB_NOT_FOUND");
        body.put("jobId", exception.getJobId());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(MapViewException.class)
    public ResponseEntity<Map<String, Object>> mapViewError(MapViewException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", exception.getCode());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    @ExceptionHandler(JobCalculationException.class)
    public ResponseEntity<Map<String, Object>> calculationError(JobCalculationException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", exception.getKind().name());
        body.put("message", exception.getMessage());
        HttpStatus status;
        switch (exception.getKind()) {
            case INVALID_REQUEST: status = HttpStatus.BAD_REQUEST; break;
            case STATE_CONFLICT:
            case RESULT_UNAVAILABLE: status = HttpStatus.CONFLICT; break;
            case QUEUE_FULL: status = HttpStatus.SERVICE_UNAVAILABLE; break;
            case RESULT_EXPIRED: status = HttpStatus.GONE; break;
            default: throw new IllegalStateException("Unhandled calculation error kind");
        }
        return ResponseEntity.status(status).body(body);
    }
}
