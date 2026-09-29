package ru.lct.heatnetwork.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import ru.lct.heatnetwork.api.RouteAttemptEvent;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.routing.RouteCandidate;
import ru.lct.heatnetwork.routing.RouteRequest;

/** Stores only bounded, independently validated previews while a calculation is active. */
@Service
public final class CalculationProgressStore {
    private static final int MAX_PREVIEW_ROUTES = 64;
    private static final int MAX_ATTEMPT_EVENTS = 256;
    private static final int MAX_REASON_LENGTH = 180;
    private static final int MAX_EVENT_ID_LENGTH = 256;
    private static final int MAX_TREE_TRAJECTORIES = 18;
    private static final int MAX_TREE_SEGMENTS = 256;
    private static final int MAX_TREE_COORDINATES = 4000;

    private final ConcurrentMap<UUID, State> jobs = new ConcurrentHashMap<>();
    private final CrsTransform crsTransform;

    public CalculationProgressStore(CrsTransform crsTransform) {
        if (crsTransform == null) throw new IllegalArgumentException("Coordinate transform is required");
        this.crsTransform = crsTransform;
    }

    public void begin(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("Job ID is required");
        jobs.put(jobId, new State(0, "PREPARING", 0, 0, 0, Collections.emptyMap(),
                Collections.emptyMap(), null, Collections.emptyList(), 0, Instant.now()));
    }

    public void configureSearch(UUID jobId, int targetCount, int totalAttempts) {
        if (targetCount < 0 || totalAttempts < 0) throw new IllegalArgumentException("Search counts cannot be negative");
        jobs.computeIfPresent(jobId, (ignored, state) -> state.copy(state.revision + 1, "ROUTE_SEARCH",
                targetCount, state.completedAttempts, totalAttempts, state.previews,
                state.events, state.truncatedEvents, Instant.now()));
    }

    public void phase(UUID jobId, String phase) {
        if (phase == null || phase.trim().isEmpty()) throw new IllegalArgumentException("Progress phase is required");
        jobs.computeIfPresent(jobId, (ignored, state) -> state.copy(state.revision + 1, phase,
                state.targetCount, state.completedAttempts, state.totalAttempts, state.previews,
                state.events, state.truncatedEvents, Instant.now()));
    }

    void recordSearchAttempt(UUID jobId, RouteRequest request, RouteCandidate candidate) {
        if (request == null || candidate == null) return;
        LineString geometry = candidate.getGeometry();
        recordAttempt(jobId, request.getTarget().getFeature().getId(),
                candidate.getStatus(), geometry);
        appendEvent(jobId, "ROUTE_SEARCH", request.getTarget().getFeature().getId(),
                request.getVariantId(), request.getAttachment().getNetworkId(),
                request.getAttachment().getEndpoint(), candidate.getStatus().name(),
                candidate.getValidation() != null && !candidate.getValidation().getIssues().isEmpty()
                        ? candidate.getValidation().getIssues().get(0).getCode() : candidate.getDetail(),
                candidate.isFound() && geometry != null ? geometry.getLength() : null);
    }

    void recordTreeAttempt(UUID jobId, TypedId targetId, String attemptId, Point destination,
            RouteCandidate.Status status, String reason, Double lengthM) {
        if (targetId == null || status == null) return;
        appendEvent(jobId, "TREE_GROWTH", targetId, attemptId, null, destination,
                status.name(), reason, lengthM);
    }

    void recordTreeTrajectory(UUID jobId, String trajectoryId, Variant checkedTree) {
        if (jobId == null || trajectoryId == null || trajectoryId.isEmpty()
                || trajectoryId.length() > MAX_EVENT_ID_LENGTH || checkedTree == null) return;
        List<TypedId> connected = new ArrayList<>();
        for (Variant.Node node : checkedTree.getNodes()) {
            if ("oks_connection_point".equals(node.getType()) && node.getInputFeatureId() != null
                    && node.getInputFeatureId().toString().length() <= MAX_EVENT_ID_LENGTH) {
                connected.add(node.getInputFeatureId());
            }
        }
        connected.sort(ru.lct.heatnetwork.domain.TypedIdOrder.INSTANCE);
        List<List<List<Double>>> segments = new ArrayList<>();
        int coordinateCount = 0;
        boolean omitted = checkedTree.getSegments().size() > MAX_TREE_SEGMENTS;
        if (!omitted) {
            for (Variant.Segment segment : checkedTree.getSegments()) {
                LineString geometry = segment.getGeometry();
                if (geometry == null || geometry.isEmpty()
                        || coordinateCount + geometry.getNumPoints() > MAX_TREE_COORDINATES) {
                    omitted = true;
                    break;
                }
                coordinateCount += geometry.getNumPoints();
                LineString geographic;
                synchronized (crsTransform) {
                    geographic = (LineString) crsTransform.toGeographic(geometry);
                }
                List<List<Double>> coordinates = new ArrayList<>();
                for (Coordinate coordinate : geographic.getCoordinates()) {
                    coordinates.add(java.util.Arrays.asList(coordinate.x, coordinate.y));
                }
                segments.add(coordinates);
            }
        }
        if (omitted) segments.clear();
        String variantId = checkedTree.getId().substring(0,
                Math.min(MAX_EVENT_ID_LENGTH, checkedTree.getId().length()));
        CalculationProgressSnapshot.TreeTrajectoryPreview preview =
                new CalculationProgressSnapshot.TreeTrajectoryPreview(trajectoryId, variantId,
                        connected, segments, omitted);
        jobs.computeIfPresent(jobId, (ignored, state) -> {
            if (!state.trajectories.containsKey(trajectoryId)
                    && state.trajectories.size() >= MAX_TREE_TRAJECTORIES) return state;
            Map<String, CalculationProgressSnapshot.TreeTrajectoryPreview> trajectories =
                    new LinkedHashMap<>(state.trajectories);
            trajectories.put(trajectoryId, preview);
            return new State(state.revision + 1, state.phase, state.targetCount,
                    state.completedAttempts, state.totalAttempts, state.previews,
                    trajectories, trajectoryId, state.events, state.truncatedEvents, Instant.now());
        });
    }

    private void appendEvent(UUID jobId, String stage, TypedId targetId, String attemptId,
            TypedId networkId, Point destination, String status, String reason, Double lengthM) {
        if (jobId == null || destination == null || destination.isEmpty()) return;
        List<Double> geographic = geographicPoint(destination);
        String boundedReason = reason == null ? null
                : reason.substring(0, Math.min(MAX_REASON_LENGTH, reason.length()));
        TypedId boundedTargetId = targetId.toString().length() > MAX_EVENT_ID_LENGTH ? null : targetId;
        TypedId boundedNetworkId = networkId != null && networkId.toString().length() > MAX_EVENT_ID_LENGTH
                ? null : networkId;
        String boundedAttemptId = attemptId == null ? null
                : attemptId.substring(0, Math.min(MAX_EVENT_ID_LENGTH, attemptId.length()));
        if (boundedTargetId == null) boundedReason = "Target ID omitted from progress: more than 256 characters";
        final String eventReason = boundedReason;
        jobs.computeIfPresent(jobId, (ignored, state) -> {
            List<RouteAttemptEvent> events = new ArrayList<>(state.events);
            events.add(new RouteAttemptEvent(state.truncatedEvents + events.size() + 1,
                    stage, boundedTargetId, boundedAttemptId, boundedNetworkId, geographic, status,
                    eventReason, lengthM));
            long truncated = state.truncatedEvents;
            if (events.size() > MAX_ATTEMPT_EVENTS) {
                events.remove(0);
                truncated++;
            }
            return state.copy(state.revision + 1, state.phase, state.targetCount,
                    state.completedAttempts, state.totalAttempts, state.previews,
                    events, truncated, Instant.now());
        });
    }

    private List<Double> geographicPoint(Point point) {
        Point geographic;
        synchronized (crsTransform) {
            geographic = (Point) crsTransform.toGeographic(point);
        }
        return java.util.Arrays.asList(geographic.getX(), geographic.getY());
    }

    void recordAttempt(UUID jobId, TypedId targetId, RouteCandidate.Status status, LineString geometry) {
        if (jobId == null || targetId == null || status == null) return;
        CalculationProgressSnapshot.RoutePreview candidate = preview(targetId, status, geometry);
        jobs.computeIfPresent(jobId, (ignored, state) -> {
            Map<TypedId, CalculationProgressSnapshot.RoutePreview> previews = new LinkedHashMap<>(state.previews);
            if (candidate != null) {
                CalculationProgressSnapshot.RoutePreview current = previews.get(targetId);
                if ((current == null && previews.size() < MAX_PREVIEW_ROUTES)
                        || (current != null && candidate.getLengthM() < current.getLengthM())) {
                    previews.put(targetId, candidate);
                }
            }
            return state.copy(state.revision + 1, state.phase, state.targetCount,
                    Math.min(state.totalAttempts, state.completedAttempts + 1), state.totalAttempts,
                    previews, state.events, state.truncatedEvents, Instant.now());
        });
    }

    public CalculationProgressSnapshot snapshot(UUID jobId) {
        State state = jobs.get(jobId);
        if (state == null) return CalculationProgressSnapshot.unavailable();
        List<CalculationProgressSnapshot.RoutePreview> previews = new ArrayList<>(state.previews.values());
        previews.sort(Comparator.comparing(CalculationProgressSnapshot.RoutePreview::getTargetId,
                ru.lct.heatnetwork.domain.TypedIdOrder.INSTANCE));
        return new CalculationProgressSnapshot(true, state.revision, state.phase, state.targetCount,
                state.completedAttempts, state.totalAttempts, previews,
                new ArrayList<>(state.trajectories.values()), state.activeTrajectoryId,
                state.events, state.truncatedEvents, state.updatedAt);
    }

    public void clear(UUID jobId) {
        if (jobId != null) jobs.remove(jobId);
    }

    private CalculationProgressSnapshot.RoutePreview preview(TypedId targetId,
            RouteCandidate.Status status, LineString geometry) {
        if (status != RouteCandidate.Status.FOUND
                && status != RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH) return null;
        if (geometry == null || geometry.isEmpty() || !Double.isFinite(geometry.getLength())) return null;
        LineString geographic;
        synchronized (crsTransform) {
            geographic = (LineString) crsTransform.toGeographic(geometry);
        }
        List<List<Double>> coordinates = new ArrayList<>();
        for (Coordinate coordinate : geographic.getCoordinates()) {
            coordinates.add(java.util.Arrays.asList(coordinate.x, coordinate.y));
        }
        return new CalculationProgressSnapshot.RoutePreview(targetId, status.name(), geometry.getLength(), coordinates);
    }

    private static final class State {
        private final long revision;
        private final String phase;
        private final int targetCount;
        private final int completedAttempts;
        private final int totalAttempts;
        private final Map<TypedId, CalculationProgressSnapshot.RoutePreview> previews;
        private final Map<String, CalculationProgressSnapshot.TreeTrajectoryPreview> trajectories;
        private final String activeTrajectoryId;
        private final List<RouteAttemptEvent> events;
        private final long truncatedEvents;
        private final Instant updatedAt;

        private State(long revision, String phase, int targetCount, int completedAttempts, int totalAttempts,
                Map<TypedId, CalculationProgressSnapshot.RoutePreview> previews,
                Map<String, CalculationProgressSnapshot.TreeTrajectoryPreview> trajectories,
                String activeTrajectoryId, List<RouteAttemptEvent> events, long truncatedEvents, Instant updatedAt) {
            this.revision = revision;
            this.phase = phase;
            this.targetCount = targetCount;
            this.completedAttempts = completedAttempts;
            this.totalAttempts = totalAttempts;
            this.previews = Collections.unmodifiableMap(new LinkedHashMap<>(previews));
            this.trajectories = Collections.unmodifiableMap(new LinkedHashMap<>(trajectories));
            this.activeTrajectoryId = activeTrajectoryId;
            this.events = Collections.unmodifiableList(RouteAttemptEvent.copyAll(events));
            this.truncatedEvents = truncatedEvents;
            this.updatedAt = updatedAt;
        }

        private State copy(long revision, String phase, int targetCount, int completedAttempts, int totalAttempts,
                Map<TypedId, CalculationProgressSnapshot.RoutePreview> previews,
                List<RouteAttemptEvent> events, long truncatedEvents, Instant updatedAt) {
            return new State(revision, phase, targetCount, completedAttempts, totalAttempts,
                    previews, trajectories, activeTrajectoryId, events, truncatedEvents, updatedAt);
        }
    }
}
