package ru.lct.heatnetwork.api;

import java.util.UUID;

/** WGS84 bounds used to open the map on the uploaded dataset. */
public final class MapExtentResponse {
    private final UUID jobId;
    private final String crs = "EPSG:4326";
    private final double[] bbox;

    public MapExtentResponse(UUID jobId, double west, double south, double east, double north) {
        this.jobId = jobId;
        this.bbox = new double[] {west, south, east, north};
    }

    public UUID getJobId() { return jobId; }
    public String getCrs() { return crs; }
    public double[] getBbox() { return bbox.clone(); }
}
