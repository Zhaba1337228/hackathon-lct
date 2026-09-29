package ru.lct.heatnetwork.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.routing.RouteCandidate;

class CalculationProgressStoreTest {
    private static final GeometryFactory GEOGRAPHIC = new GeometryFactory(new PrecisionModel(), 4326);
    private final CrsTransform transform = new CrsTransform();
    private final CalculationProgressStore store = new CalculationProgressStore(transform);

    @Test
    void publishesOnlyFoundCandidatesAndKeepsTheShortestRoutePerTarget() {
        UUID jobId = UUID.randomUUID();
        TypedId targetId = TypedId.of("building-a");
        store.begin(jobId);
        store.configureSearch(jobId, 2, 3);

        store.recordAttempt(jobId, targetId, RouteCandidate.Status.FOUND, projectedLine(0.0001));
        CalculationProgressSnapshot first = store.snapshot(jobId);
        assertTrue(first.isAvailable());
        assertEquals(1, first.getCompletedAttempts());
        assertEquals(3, first.getTotalAttempts());
        assertEquals(1, first.getPreviewRoutes().size());
        assertEquals(targetId, first.getPreviewRoutes().get(0).getTargetId());
        assertEquals("FOUND", first.getPreviewRoutes().get(0).getStatus());
        assertEquals(37.6, first.getPreviewRoutes().get(0).getCoordinates().get(0).get(0), 1e-5);
        assertEquals(55.75, first.getPreviewRoutes().get(0).getCoordinates().get(0).get(1), 1e-5);

        store.recordAttempt(jobId, targetId, RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH,
                projectedLine(0.0002));
        CalculationProgressSnapshot longer = store.snapshot(jobId);
        assertTrue(longer.getRevision() > first.getRevision());
        assertEquals(2, longer.getCompletedAttempts());
        assertEquals(first.getPreviewRoutes().get(0).getLengthM(),
                longer.getPreviewRoutes().get(0).getLengthM(), 1e-6);

        store.recordAttempt(jobId, targetId, RouteCandidate.Status.VALIDATION_REJECTED,
                projectedLine(0.00001));
        CalculationProgressSnapshot rejected = store.snapshot(jobId);
        assertEquals(3, rejected.getCompletedAttempts());
        assertEquals(1, rejected.getPreviewRoutes().size());
        assertEquals(first.getPreviewRoutes().get(0).getLengthM(),
                rejected.getPreviewRoutes().get(0).getLengthM(), 1e-6);
    }

    @Test
    void progressIsIsolatedByJobAndCanBeClearedAtTerminalState() {
        UUID firstJob = UUID.randomUUID();
        UUID secondJob = UUID.randomUUID();
        store.begin(firstJob);
        store.begin(secondJob);
        store.configureSearch(firstJob, 1, 1);
        store.configureSearch(secondJob, 2, 2);

        store.clear(firstJob);

        assertFalse(store.snapshot(firstJob).isAvailable());
        assertTrue(store.snapshot(secondJob).isAvailable());
        assertEquals(2, store.snapshot(secondJob).getTargetCount());
    }

    @Test
    void boundsAttemptHistoryAndPreservesSnapshotImmutability() {
        UUID jobId = UUID.randomUUID();
        TypedId targetId = TypedId.of("building-a");
        Point destination = (Point) transform.toProjected(
                GEOGRAPHIC.createPoint(new Coordinate(37.6, 55.75)));
        store.begin(jobId);
        for (int index = 0; index < 260; index++) {
            store.recordTreeAttempt(jobId, targetId, "attempt-" + index, destination,
                    RouteCandidate.Status.TREE_BRANCH_REJECTED, "blocked", null);
        }

        CalculationProgressSnapshot snapshot = store.snapshot(jobId);
        assertEquals(256, snapshot.getAttemptEvents().size());
        assertEquals(4, snapshot.getTruncatedAttemptEvents());
        assertEquals(5, snapshot.getAttemptEvents().get(0).getSequence());
        assertEquals(260, snapshot.getAttemptEvents().get(255).getSequence());
        assertEquals(37.6, snapshot.getAttemptEvents().get(0).getDestination().get(0), 1e-5);
        assertEquals(55.75, snapshot.getAttemptEvents().get(0).getDestination().get(1), 1e-5);
        snapshot.getAttemptEvents().get(0).setStatus("MUTATED");
        assertEquals("TREE_BRANCH_REJECTED", store.snapshot(jobId).getAttemptEvents().get(0).getStatus());
        store.clear(jobId);
        assertFalse(store.snapshot(jobId).isAvailable());
    }

    @Test
    void boundsLongTelemetryFieldsWithoutChangingSourceIdentifiers() {
        UUID jobId = UUID.randomUUID();
        String longId = "x".repeat(300);
        Point destination = (Point) transform.toProjected(
                GEOGRAPHIC.createPoint(new Coordinate(37.6, 55.75)));
        store.begin(jobId);
        store.recordTreeAttempt(jobId, TypedId.of(longId), longId, destination,
                RouteCandidate.Status.TREE_BRANCH_REJECTED, longId, null);

        ru.lct.heatnetwork.api.RouteAttemptEvent event = store.snapshot(jobId).getAttemptEvents().get(0);
        assertEquals(null, event.getTargetId());
        assertEquals(256, event.getAttemptId().length());
        assertTrue(event.getReason().length() <= 180);
    }

    @Test
    void keepsWholeValidatedTreeSnapshotsSeparateWithoutChangingRouteAttemptCount() {
        UUID jobId = UUID.randomUUID();
        TypedId targetId = TypedId.of("building-a");
        store.begin(jobId);
        store.configureSearch(jobId, 1, 3);
        store.recordTreeTrajectory(jobId, "first_valid:seed0",
                tree("first", targetId, projectedLine(0.0001), false));
        CalculationProgressSnapshot seed = store.snapshot(jobId);
        store.recordTreeTrajectory(jobId, "shortest_valid:seed0",
                tree("second", targetId, projectedLine(0.0002), false));
        store.recordTreeTrajectory(jobId, "first_valid:seed0",
                tree("first-grown", targetId, projectedLine(0.0001), true));
        store.recordTreeAttempt(jobId, targetId, "rejected", null,
                RouteCandidate.Status.TREE_BRANCH_REJECTED, "blocked", null);

        CalculationProgressSnapshot current = store.snapshot(jobId);
        assertEquals(0, current.getCompletedAttempts());
        assertEquals(2, current.getTreeTrajectories().size());
        assertEquals("first_valid:seed0", current.getActiveTrajectoryId());
        assertEquals(1, seed.getTreeTrajectories().get(0).getSegments().size());
        assertEquals(2, current.getTreeTrajectories().get(0).getSegments().size());
        assertEquals(1, current.getTreeTrajectories().get(1).getSegments().size());
        assertEquals(targetId, current.getTreeTrajectories().get(0).getConnectedTargetIds().get(0));
        assertEquals(37.6, current.getTreeTrajectories().get(0).getSegments()
                .get(0).get(0).get(0), 1e-5);
        assertTrue(current.getRevision() > seed.getRevision());
        assertThrows(UnsupportedOperationException.class, () -> current.getTreeTrajectories()
                .get(0).getSegments().get(0).get(0).set(0, 0.0));
        assertTrue(current.getPreviewRoutes().isEmpty());
    }

    @Test
    void keepsAllEighteenNavigatorTrajectoriesVisibleWithCombinedGroups() {
        UUID jobId = UUID.randomUUID();
        TypedId targetId = TypedId.of("building-a");
        Variant tree = tree("checked", targetId, projectedLine(0.0001), false);
        store.begin(jobId);
        List<String> names = Arrays.asList("group:seed0", "group:seed1",
                "portfolio:seed0", "portfolio:seed1",
                "first_valid:seed0", "first_valid:seed1", "first_valid:seed2",
                "shortest_valid:seed0", "shortest_valid:seed1", "shortest_valid:seed2",
                "group:combined0", "group:combined1", "group:seed2", "group:seed3", "group:seed4",
                "group:seed0:district", "group:seed1:district", "group:seed2:district");
        for (String name : names) store.recordTreeTrajectory(jobId, name, tree);

        CalculationProgressSnapshot snapshot = store.snapshot(jobId);
        assertEquals(18, snapshot.getTreeTrajectories().size());
        assertEquals("group:seed2:district", snapshot.getActiveTrajectoryId());
        store.recordTreeTrajectory(jobId, "overflow:seed0", tree);
        assertEquals(18, store.snapshot(jobId).getTreeTrajectories().size());
        assertEquals("group:seed2:district", store.snapshot(jobId).getActiveTrajectoryId());
    }

    private Variant tree(String id, TypedId targetId, LineString geometry, boolean split) {
        Coordinate start = geometry.getCoordinateN(0);
        Coordinate end = geometry.getCoordinateN(1);
        List<Variant.Node> nodes = new ArrayList<>(Arrays.asList(
                new Variant.Node("target", start.x, start.y, "oks_connection_point", targetId),
                new Variant.Node("source", end.x, end.y, "heat_chamber")));
        List<Variant.Segment> segments = new ArrayList<>();
        if (split) {
            Coordinate middle = new Coordinate((start.x + end.x) / 2, (start.y + end.y) / 2);
            nodes.add(new Variant.Node("split", middle.x, middle.y, "heat_chamber"));
            LineString before = geometry.getFactory().createLineString(new Coordinate[] {start, middle});
            LineString after = geometry.getFactory().createLineString(new Coordinate[] {middle, end});
            before.setSRID(32637);
            after.setSRID(32637);
            segments.add(segment("before", "target", "split", before));
            segments.add(segment("after", "split", "source", after));
        } else segments.add(segment("old-trunk", "target", "source", geometry));
        return new Variant(id, nodes, segments);
    }

    private Variant.Segment segment(String id, String from, String to, LineString geometry) {
        return new Variant.Segment(id, from, to, geometry, from, to, BigDecimal.ONE, 50,
                "base", Collections.emptySet(), BigDecimal.ONE);
    }

    private LineString projectedLine(double longitudeDelta) {
        LineString geographic = GEOGRAPHIC.createLineString(new Coordinate[] {
                new Coordinate(37.6, 55.75), new Coordinate(37.6 + longitudeDelta, 55.75)
        });
        return (LineString) transform.toProjected(geographic);
    }
}
