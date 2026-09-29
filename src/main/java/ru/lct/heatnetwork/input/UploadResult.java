package ru.lct.heatnetwork.input;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Accepted input and its validation summary")
public final class UploadResult {
    private final UUID jobId;
    private final long receivedBytes;
    private final long featureCount;
    private final Map<String, Long> countsByType;

    public UploadResult(UUID jobId, long receivedBytes, GeoJsonStreamReader.Summary summary) {
        this.jobId = jobId;
        this.receivedBytes = receivedBytes;
        this.featureCount = summary.getFeatureCount();
        this.countsByType = Collections.unmodifiableMap(new LinkedHashMap<>(summary.getCountsByType()));
    }

    public UUID getJobId() { return jobId; }
    public String getStage() { return "VALIDATED"; }
    public String getStatus() { return "READY"; }
    public String getValidationStatus() { return "PASSED"; }
    public String getCalculationStatus() { return "NOT_STARTED"; }
    public long getReceivedBytes() { return receivedBytes; }
    public long getFeatureCount() { return featureCount; }
    public Map<String, Long> getCountsByType() { return countsByType; }
}
