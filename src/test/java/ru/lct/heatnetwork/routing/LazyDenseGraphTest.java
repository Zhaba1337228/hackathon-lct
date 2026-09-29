package ru.lct.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class LazyDenseGraphTest {
    @Test
    void skipsUnvisitedPairsWithoutRemovingAnyNeighbor() throws Exception {
        List<Coordinate> points = new ArrayList<>(Arrays.asList(new Coordinate(0, 0), new Coordinate(10, 0)));
        for (int i = 0; i < 120; i++) points.add(new Coordinate(i, 50 + i));
        SingleTargetRouter.DenseGraphResult result = SingleTargetRouter.searchDenseGraph(points, null, -1,
                (first, second) -> true);
        assertEquals(10, length(result.path), 1e-9);
        assertEquals(121, result.exactChecks);
        assertEquals(7381, points.size() * (points.size() - 1) / 2);

        points.add(new Coordinate(3, 2));
        points.add(new Coordinate(7, 2));
        org.locationtech.jts.geom.Geometry obstacle = new org.locationtech.jts.io.WKTReader().read(
                "POLYGON ((4 -1, 6 -1, 6 1, 4 1, 4 -1))");
        boolean[][] edges = new boolean[points.size()][points.size()];
        for (int i = 0; i < edges.length; i++) for (int j = i + 1; j < edges.length; j++)
            edges[i][j] = edges[j][i] = !obstacle.intersects(obstacle.getFactory().createLineString(
                    new Coordinate[] {points.get(i), points.get(j)}));
        assertFalse(edges[0][1], "The real graph is entered only after a failed direct attempt");
        SingleTargetRouter.DenseGraphResult detour = SingleTargetRouter.searchDenseGraph(points, null, -1,
                (first, second) -> edges[first][second]);
        assertEquals(dijkstra(points, edges, null, -1), length(detour.path), 1e-7);
        int eagerChecks = points.size() * (points.size() - 1) / 2;
        assertTrue(detour.exactChecks < eagerChecks / 4);
        System.out.println("Blocked-direct dense graph: lazyChecks=" + detour.exactChecks
                + " eagerChecks=" + eagerChecks + " routeLength=" + length(detour.path));
    }

    @Test
    void matchesIndependentFullGraphDijkstraWithTurnsAndMandatoryVia() {
        Random random = new Random(29);
        for (int scene = 0; scene < 60; scene++) {
            List<Coordinate> points = new ArrayList<>(Arrays.asList(new Coordinate(0, 0), new Coordinate(20, 0)));
            for (int i = 0; i < 10; i++) points.add(new Coordinate(random.nextDouble() * 30 - 5,
                    random.nextDouble() * 30 - 15));
            boolean[][] edges = new boolean[points.size()][points.size()];
            for (int i = 0; i < edges.length; i++) for (int j = i + 1; j < edges.length; j++)
                edges[i][j] = edges[j][i] = random.nextDouble() > 0.35;
            int via = scene % 2 == 0 ? -1 : 2;
            Coordinate approach = scene % 3 == 0 ? new Coordinate(-5, 0) : null;
            Set<String> checked = new HashSet<>();
            SingleTargetRouter.DenseGraphResult result = SingleTargetRouter.searchDenseGraph(points, approach,
                    via, (first, second) -> {
                assertTrue(checked.add(first + ":" + second), "Each pair is evaluated once");
                return edges[first][second];
            });
            double expected = dijkstra(points, edges, approach, via);
            if (Double.isInfinite(expected)) assertNull(result.path, "scene " + scene);
            else {
                assertNotNull(result.path, "scene " + scene);
                assertEquals(expected, length(result.path), 1e-7, "scene " + scene);
                assertTrue(SingleTargetRouter.legalPolylineTurns(result.path, approach));
                if (via >= 0) assertTrue(result.path.contains(points.get(via)));
                List<Coordinate> repeated = SingleTargetRouter.searchDenseGraph(points, approach, via,
                        (first, second) -> edges[first][second]).path;
                assertEquals(result.path, repeated);
            }
        }
    }

    @Test
    void unreachableGraphStaysUnreachable() {
        assertNull(SingleTargetRouter.searchDenseGraph(Arrays.asList(new Coordinate(0, 0), new Coordinate(1, 0)),
                null, -1, (first, second) -> false).path);
    }

    private static double length(List<Coordinate> path) {
        double length = 0;
        for (int i = 1; i < path.size(); i++) length += path.get(i - 1).distance(path.get(i));
        return length;
    }

    // Full graph reference: uniform-cost search with a separate angle calculation and no heuristic.
    private static double dijkstra(List<Coordinate> points, boolean[][] edges, Coordinate approach, int via) {
        PriorityQueue<double[]> queue = new PriorityQueue<>(Comparator.comparingDouble(s -> s[3]));
        Map<String, Double> distances = new HashMap<>();
        queue.add(new double[] {-1, 0, via == 0 ? 1 : 0, 0});
        while (!queue.isEmpty()) {
            double[] state = queue.remove();
            int previous = (int) state[0], current = (int) state[1];
            boolean visitedVia = state[2] == 1;
            String key = previous + ":" + current + ":" + visitedVia;
            if (state[3] >= distances.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
            distances.put(key, state[3]);
            if (current == 1 && (via < 0 || visitedVia)) return state[3];
            for (int next = 0; next < points.size(); next++) {
                if (!edges[current][next] || (next == 1 && via >= 0 && !visitedVia)) continue;
                Coordinate before = previous < 0 ? approach : points.get(previous);
                Coordinate at = points.get(current), after = points.get(next);
                if (before != null && (before.x - at.x) * (after.x - at.x)
                        + (before.y - at.y) * (after.y - at.y) > 1e-9) continue;
                queue.add(new double[] {current, next, visitedVia || next == via ? 1 : 0,
                        state[3] + at.distance(after)});
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
