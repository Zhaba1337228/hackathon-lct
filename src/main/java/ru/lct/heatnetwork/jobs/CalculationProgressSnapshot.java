package ru.lct.heatnetwork.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.lct.heatnetwork.api.RouteAttemptEvent;
import ru.lct.heatnetwork.domain.TypedId;

/** Immutable view of the live, non-final route-search progress for one job. */
public final class CalculationProgressSnapshot {
    private final boolean available;
    private final long revision;
    private final String phase;
    private final int targetCount;
    private final int completedAttempts;
    private final int totalAttempts;
    private final List<RoutePreview> previewRoutes;
    private final List<TreeTrajectoryPreview> treeTrajectories;
    private final String activeTrajectoryId;
    private final List<RouteAttemptEvent> attemptEvents;
    private final long truncatedAttemptEvents;
    private final Instant updatedAt;

    CalculationProgressSnapshot(boolean available, long revision, String phase, int targetCount,
            int completedAttempts, int totalAttempts, List<RoutePreview> previewRoutes,
            List<TreeTrajectoryPreview> treeTrajectories, String activeTrajectoryId,
            List<RouteAttemptEvent> attemptEvents, long truncatedAttemptEvents, Instant updatedAt) {
        this.available = available;
        this.revision = revision;
        this.phase = phase;
        this.targetCount = targetCount;
        this.completedAttempts = completedAttempts;
        this.totalAttempts = totalAttempts;
        this.previewRoutes = Collections.unmodifiableList(new ArrayList<>(previewRoutes));
        this.treeTrajectories = Collections.unmodifiableList(new ArrayList<>(treeTrajectories));
        this.activeTrajectoryId = activeTrajectoryId;
        this.attemptEvents = Collections.unmodifiableList(RouteAttemptEvent.copyAll(attemptEvents));
        this.truncatedAttemptEvents = truncatedAttemptEvents;
        this.updatedAt = updatedAt;
    }

    static CalculationProgressSnapshot unavailable() {
        return new CalculationProgressSnapshot(false, 0, "NOT_AVAILABLE", 0, 0, 0,
                Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), 0, null);
    }

    public boolean isAvailable() { return available; }
    public long getRevision() { return revision; }
    public String getPhase() { return phase; }
    public int getTargetCount() { return targetCount; }
    public int getCompletedAttempts() { return completedAttempts; }
    public int getTotalAttempts() { return totalAttempts; }
    public List<RoutePreview> getPreviewRoutes() { return previewRoutes; }
    public List<TreeTrajectoryPreview> getTreeTrajectories() { return treeTrajectories; }
    public String getActiveTrajectoryId() { return activeTrajectoryId; }
    public List<RouteAttemptEvent> getAttemptEvents() {
        return Collections.unmodifiableList(RouteAttemptEvent.copyAll(attemptEvents));
    }
    public long getTruncatedAttemptEvents() { return truncatedAttemptEvents; }
    public Instant getUpdatedAt() { return updatedAt; }

    public static final class RoutePreview {
        private final TypedId targetId;
        private final String status;
        private final double lengthM;
        private final List<List<Double>> coordinates;

        RoutePreview(TypedId targetId, String status, double lengthM, List<List<Double>> coordinates) {
            this.targetId = targetId;
            this.status = status;
            this.lengthM = lengthM;
            List<List<Double>> copied = new ArrayList<>(coordinates.size());
            for (List<Double> coordinate : coordinates) {
                copied.add(Collections.unmodifiableList(new ArrayList<>(coordinate)));
            }
            this.coordinates = Collections.unmodifiableList(copied);
        }

        public TypedId getTargetId() { return targetId; }
        public String getStatus() { return status; }
        public double getLengthM() { return lengthM; }
        public List<List<Double>> getCoordinates() { return coordinates; }
    }

    public static final class TreeTrajectoryPreview {
        private final String trajectoryId;
        private final String variantId;
        private final List<TypedId> connectedTargetIds;
        private final List<List<List<Double>>> segments;
        private final boolean geometryOmitted;

        TreeTrajectoryPreview(String trajectoryId, String variantId, List<TypedId> connectedTargetIds,
                List<List<List<Double>>> segments, boolean geometryOmitted) {
            this.trajectoryId = trajectoryId;
            this.variantId = variantId;
            this.connectedTargetIds = Collections.unmodifiableList(new ArrayList<>(connectedTargetIds));
            List<List<List<Double>>> copied = new ArrayList<>(segments.size());
            for (List<List<Double>> segment : segments) {
                List<List<Double>> coordinates = new ArrayList<>(segment.size());
                for (List<Double> coordinate : segment) {
                    coordinates.add(Collections.unmodifiableList(new ArrayList<>(coordinate)));
                }
                copied.add(Collections.unmodifiableList(coordinates));
            }
            this.segments = Collections.unmodifiableList(copied);
            this.geometryOmitted = geometryOmitted;
        }

        public String getTrajectoryId() { return trajectoryId; }
        public String getVariantId() { return variantId; }
        public List<TypedId> getConnectedTargetIds() { return connectedTargetIds; }
        public List<List<List<Double>>> getSegments() { return segments; }
        public boolean isGeometryOmitted() { return geometryOmitted; }
    }
}
