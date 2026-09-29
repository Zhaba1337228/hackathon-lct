package ru.lct.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

class AdaptiveQuadtreePathfinderTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final AdaptiveQuadtreePathfinder pathfinder = new AdaptiveQuadtreePathfinder();

    @Test
    void refinesAroundAnObstacleAndFindsAPathThroughANarrowOpening() {
        Geometry area = rectangle(0, 0, 100, 40);
        Geometry lowerWall = rectangle(45, 0, 55, 18);
        Geometry upperWall = rectangle(45, 22, 55, 40);
        List<Geometry> blockers = Arrays.asList(lowerWall, upperWall);

        AdaptiveQuadtreePathfinder.SearchResult result = pathfinder.search(area,
                new Coordinate(10, 20), new Coordinate(90, 20), factory,
                blockers, blockers, Collections.emptyList(), 20000, 100000, 1.0,
                edge -> blockers.stream().noneMatch(obstacle -> obstacle.intersects(edge)),
                (previous, current, next) -> true);

        assertNotNull(result.getRoute(), result.getStatus() + ": " + result.getDetail()
                + " cells=" + result.getCells() + " nodes=" + result.getNodes()
                + " edges=" + result.getEdges() + " checks=" + result.getEdgeChecks());
        assertTrue(result.getRoute().isValid());
        assertTrue(result.getCells() > 1, "the obstacle boundary should trigger local refinement");
        assertTrue(result.getEdgeChecks() > 0);
        assertEquals(0, result.getBalanceViolations(), "neighboring leaves must remain 2:1 balanced");
        for (Geometry blocker : blockers) assertFalse(blocker.intersects(result.getRoute()));
    }

    @Test
    void representsASpecialPassageAsOneLongStraightTransition() {
        Geometry area = rectangle(0, 0, 100, 40);
        Geometry road = rectangle(45, 0, 55, 40);
        AdaptiveQuadtreePathfinder.SearchResult result = pathfinder.search(area,
                new Coordinate(10, 20), new Coordinate(90, 20), factory,
                Collections.emptyList(), Collections.singletonList(road), Collections.singletonList(road),
                20000, 100000, 1.0,
                edge -> !road.intersects(edge) || edge.getLength() >= 16.0,
                (previous, current, next) -> true);

        assertNotNull(result.getRoute(), result.getStatus() + ": " + result.getDetail()
                + " cells=" + result.getCells() + " nodes=" + result.getNodes()
                + " edges=" + result.getEdges() + " checks=" + result.getEdgeChecks());
        boolean hasCompleteCrossing = false;
        Coordinate[] coordinates = result.getRoute().getCoordinates();
        for (int i = 1; i < coordinates.length; i++) {
            LineString segment = factory.createLineString(new Coordinate[] { coordinates[i - 1], coordinates[i] });
            if (road.intersects(segment) && segment.getLength() >= 16.0) hasCompleteCrossing = true;
        }
        assertTrue(hasCompleteCrossing, "the road crossing must be one exact-checked macro edge");
        assertEquals(0, result.getBalanceViolations());
    }

    @Test
    void reportsIncompleteInsteadOfNoPathWhenCellBudgetStopsRefinement() {
        Geometry area = rectangle(0, 0, 100, 100);
        Geometry wall = rectangle(49, 10, 51, 90);
        AdaptiveQuadtreePathfinder.SearchResult result = pathfinder.search(area,
                new Coordinate(10, 50), new Coordinate(90, 50), factory,
                Collections.singletonList(wall), Collections.singletonList(wall), Collections.emptyList(),
                4, 1, 0.5,
                edge -> false,
                (previous, current, next) -> true);

        assertEquals(AdaptiveQuadtreePathfinder.Status.INCOMPLETE, result.getStatus());
        assertTrue(result.getUnresolvedCells() > 0);
        assertTrue(result.getDetail().contains("before ruling out a route"));
    }

    private Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        Geometry geometry = factory.toGeometry(new Envelope(minX, maxX, minY, maxY));
        geometry.setSRID(32637);
        return geometry;
    }
}
