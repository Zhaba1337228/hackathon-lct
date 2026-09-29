package ru.lct.heatnetwork.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** GeoJSON viewport page plus job state and independent per-layer cursors. */
public final class MapViewResponse {
    private final String type = "FeatureCollection";
    private final UUID jobId;
    private final String variantId;
    private final JobStatusResponse job;
    private final List<JsonNode> features;
    private final String nextInputCursor;
    private final String nextVariantCursor;

    public MapViewResponse(UUID jobId, String variantId, JobStatusResponse job, List<JsonNode> features,
            String nextInputCursor, String nextVariantCursor) {
        this.jobId = jobId;
        this.variantId = variantId;
        this.job = job;
        this.features = Collections.unmodifiableList(new ArrayList<>(features));
        this.nextInputCursor = nextInputCursor;
        this.nextVariantCursor = nextVariantCursor;
    }

    public String getType() { return type; }
    public UUID getJobId() { return jobId; }
    public String getVariantId() { return variantId; }
    public JobStatusResponse getJob() { return job; }
    public List<JsonNode> getFeatures() { return features; }
    public String getNextInputCursor() { return nextInputCursor; }
    public String getNextVariantCursor() { return nextVariantCursor; }
}
