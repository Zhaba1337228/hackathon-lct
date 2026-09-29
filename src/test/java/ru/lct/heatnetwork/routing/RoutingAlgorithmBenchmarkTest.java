package ru.lct.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.spatial.ClearanceCheck;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.spatial.IntersectionSegment;
import ru.lct.heatnetwork.spatial.SpecialCrossingBuilder;
import ru.lct.heatnetwork.validation.ValidationContext;
import ru.lct.heatnetwork.validation.ValidationIssue;
import ru.lct.heatnetwork.validation.ValidationReport;
import ru.lct.heatnetwork.validation.VariantValidator;

/** Fixed, repeatable comparison of a sparse visibility graph and an 8-connected local grid. */
class RoutingAlgorithmBenchmarkTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 32637);
    private static final double GRID_STEP_M = 1.0;
    private static final TypedId TARGET_ID = TypedId.of("target-1");
    private static final TypedId NETWORK_ID = TypedId.of("network-1");
    private static final TypedId PARK_ID = TypedId.of("park-1");
    private static final TypedId ROAD_ID = TypedId.of("road-1");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void comparesBothSearchesOnTheSameClearanceCorridorAndChecksSpecialPassage() throws Exception {
        Point start = point(0, 20);
        Point goal = point(60, 20);
        Geometry park = projected("POLYGON ((25 10, 35 10, 35 30, 25 30, 25 10))");
        BigDecimal pairWidth = RuleCatalog.diameter(50).get().getPairWidthM();
        double clearance = new ClearanceCheck().requiredCenterlineDistanceM("park", 50, pairWidth, null);
        Geometry parkEnvelope = park.buffer(clearance + 0.25, 8);
        List<Geometry> blocked = Collections.singletonList(parkEnvelope);
        Envelope searchArea = new Envelope(0, 60, 0, 40);

        Benchmark visibility = measure(() -> new VisibilityGraphRouter().route(start, goal, blocked));
        Benchmark grid = measure(() -> new GridRouter(searchArea, GRID_STEP_M).route(start, goal, blocked));

        assertTrue(visibility.result.route.getLength() <= grid.result.route.getLength() + 1.0,
                "Visibility route should remain close to the grid route length");
        assertTrue(visibility.result.route.getLength() <= 70.0);
        assertTrue(grid.result.route.getNumPoints() <= 5,
                "Line-of-sight simplification must remove grid stair-steps");
        assertLegalTurns(visibility.result.route);
        assertLegalTurns(grid.result.route);
        assertValidatorAccepts(visibility.result.route, park);
        assertValidatorAccepts(grid.result.route, park);

        Geometry road = projected("POLYGON ((25 10, 35 10, 35 30, 25 30, 25 10))");
        RouteRun specialVisibility = new VisibilityGraphRouter().route(start, goal, Collections.emptyList());
        RouteRun specialGrid = new GridRouter(searchArea, GRID_STEP_M).route(start, goal, Collections.emptyList());
        assertValidatorAccepts(specialVisibility.route, null, road);
        assertValidatorAccepts(specialGrid.route, null, road);

        System.out.format("routing benchmark step=%.1fm; visibility vertices=%d expanded=%d length=%.3fm points=%d median=%dus; grid states-discovered=%d expanded=%d length=%.3fm points=%d median=%dus%n",
                GRID_STEP_M, visibility.result.candidateCount, visibility.result.expandedStates,
                visibility.result.route.getLength(), visibility.result.route.getNumPoints(), visibility.medianNanos / 1000,
                grid.result.candidateCount, grid.result.expandedStates, grid.result.route.getLength(),
                grid.result.route.getNumPoints(), grid.medianNanos / 1000);
    }

    private void assertValidatorAccepts(LineString route, Geometry park) throws Exception {
        assertValidatorAccepts(route, park, null);
    }

    private void assertValidatorAccepts(LineString route, Geometry park, Geometry road) throws Exception {
        List<Variant.Node> nodes = new ArrayList<>();
        List<Variant.Segment> segments = new ArrayList<>();
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        Point targetPoint = point(0, 20);
        nodes.add(new Variant.Node("target", 0, 20, "oks_connection_point", TARGET_ID));
        inputs.add(indexed(TARGET_ID, "oks_connection_point", targetPoint, "flow_tph", new BigDecimal("3.0")));
        inputs.add(indexed(NETWORK_ID, "heat_network", projected("LINESTRING (60 18, 60 22)"), "diameter", 500));
        if (park != null) inputs.add(indexed(PARK_ID, "restriction", park, "restriction_type", "park"));
        if (road != null) inputs.add(indexed(ROAD_ID, "restriction", road, "restriction_type", "road"));

        nodes.add(new Variant.Node("attachment", 60, 20, "heat_chamber", NETWORK_ID));
        if (road == null) {
            segments.add(segment("route", "target", "attachment", route, "target", "attachment",
                    "base", Collections.emptySet(), BigDecimal.ONE));
        } else {
            List<IntersectionSegment> parts = new SpecialCrossingBuilder().build(route,
                    Collections.singletonList(new SpecialCrossingBuilder.CrossingRestriction(
                            ROAD_ID, "road", road, null)), 50, true, true);
            LengthIndexedLine indexed = new LengthIndexedLine(route);
            String fromNode = "target";
            for (int i = 0; i < parts.size(); i++) {
                IntersectionSegment part = parts.get(i);
                String toNode = i == parts.size() - 1 ? "attachment" : "passage-boundary-" + i;
                Coordinate end = indexed.extractPoint(part.getEndMeasureM());
                if (i < parts.size() - 1) nodes.add(new Variant.Node(toNode, end.x, end.y, "technical_node"));
                LineString geometry = (LineString) indexed.extractLine(part.getStartMeasureM(), part.getEndMeasureM());
                String method = part.getMethod() == IntersectionSegment.Method.SPECIAL ? "special" : "base";
                segments.add(segment("route-" + i, fromNode, toNode, geometry, fromNode, toNode,
                        method, part.getRestrictionIds(), part.getMultiplier()));
                fromNode = toNode;
            }
        }
        Variant variant = new Variant("benchmark", nodes, segments);
        ValidationReport report = new VariantValidator().validate(variant, new ValidationContext(inputs));
        List<String> problems = new ArrayList<>();
        for (ValidationIssue issue : report.getIssues()) problems.add(issue.getCode() + ":" + issue.getMessage());
        assertTrue(report.isAcceptable(), () -> "TSK-011 rejected benchmark route: " + problems
                + " notRun=" + report.getChecksNotRun() + " route=" + route.toText());
    }

    private Variant.Segment segment(String id, String start, String end, LineString geometry,
            String flowFrom, String flowTo, String method, java.util.Set<TypedId> restrictions,
            BigDecimal multiplier) {
        return new Variant.Segment(id, start, end, geometry, flowFrom, flowTo,
                new BigDecimal("3.0"), 50, method, restrictions, multiplier);
    }

    private FeatureIndex.IndexedFeature indexed(TypedId id, String type, Geometry geometry,
            String property, Object value) {
        FeatureProperties properties = new FeatureProperties(id, type);
        properties.putExtra(property, mapper.valueToTree(value));
        return new FeatureIndex.IndexedFeature(new InputFeature(properties, NullNode.getInstance()), geometry);
    }

    private static Benchmark measure(Search search) {
        RouteRun result = null;
        for (int i = 0; i < 3; i++) result = search.run();
        long[] samples = new long[ nineSamples() ];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            result = search.run();
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return new Benchmark(result, samples[samples.length / 2]);
    }

    private static int nineSamples() { return 9; }

    private static void assertLegalTurns(LineString route) {
        Coordinate[] points = route.getCoordinates();
        for (int i = 1; i < points.length - 1; i++) {
            assertTrue(legalTurn(points[i - 1], points[i], points[i + 1]), "Turn exceeds 90 degrees at " + i);
        }
    }

    private static boolean legalTurn(Coordinate previous, Coordinate current, Coordinate next) {
        double ax = current.x - previous.x, ay = current.y - previous.y;
        double bx = next.x - current.x, by = next.y - current.y;
        return ax * bx + ay * by >= -1e-9;
    }

    private static boolean clear(LineString route, List<Geometry> blocked) {
        for (Geometry obstacle : blocked) {
            Geometry intersection = route.intersection(obstacle);
            Geometry interior = intersection.difference(obstacle.getBoundary());
            if (interior.getLength() > 1e-7) return false;
        }
        return true;
    }

    private static LineString line(Coordinate from, Coordinate to) {
        return GEOMETRY_FACTORY.createLineString(new Coordinate[] {new Coordinate(from), new Coordinate(to)});
    }

    private static Point point(double x, double y) { return GEOMETRY_FACTORY.createPoint(new Coordinate(x, y)); }

    private static Geometry projected(String wkt) throws Exception {
        Geometry geometry = new WKTReader(GEOMETRY_FACTORY).read(wkt);
        geometry.setSRID(32637);
        return geometry;
    }

    private interface Search { RouteRun run(); }

    private static final class Benchmark {
        private final RouteRun result;
        private final long medianNanos;
        private Benchmark(RouteRun result, long medianNanos) { this.result = result; this.medianNanos = medianNanos; }
    }

    private static final class RouteRun {
        private final LineString route;
        private final int candidateCount;
        private final int expandedStates;
        private RouteRun(LineString route, int candidateCount, int expandedStates) {
            this.route = route; this.candidateCount = candidateCount; this.expandedStates = expandedStates;
        }
    }

    private static final class VisibilityGraphRouter {
        private RouteRun route(Point start, Point goal, List<Geometry> blocked) {
            List<Coordinate> vertices = new ArrayList<>();
            vertices.add(start.getCoordinate());
            vertices.add(goal.getCoordinate());
            Map<String, Boolean> seen = new HashMap<>();
            seen.put(key(start.getCoordinate()), true);
            seen.put(key(goal.getCoordinate()), true);
            for (Geometry obstacle : blocked) {
                for (Coordinate coordinate : obstacle.getBoundary().getCoordinates()) {
                    if (seen.put(key(coordinate), true) == null) vertices.add(new Coordinate(coordinate));
                }
            }
            List<List<Integer>> adjacency = new ArrayList<>();
            for (int i = 0; i < vertices.size(); i++) adjacency.add(new ArrayList<Integer>());
            for (int i = 0; i < vertices.size(); i++) {
                for (int j = i + 1; j < vertices.size(); j++) {
                    if (clear(line(vertices.get(i), vertices.get(j)), blocked)) {
                        adjacency.get(i).add(j);
                        adjacency.get(j).add(i);
                    }
                }
            }
            Comparator<GraphState> order = Comparator.comparingDouble((GraphState state) -> state.estimate)
                    .thenComparingDouble(state -> state.cost);
            PriorityQueue<GraphState> open = new PriorityQueue<>(order);
            Map<Integer, Double> best = new HashMap<>();
            Map<Integer, GraphState> parent = new HashMap<>();
            GraphState initial = new GraphState(-1, 0, 0, vertices.get(0).distance(vertices.get(1)), vertices.size());
            open.add(initial);
            best.put(initial.key, 0.0);
            int expanded = 0;
            while (!open.isEmpty()) {
                GraphState current = open.remove();
                if (current.cost > best.get(current.key) + 1e-9) continue;
                expanded++;
                if (current.current == 1) {
                    LineString raw = path(current, parent, vertices);
                    List<Coordinate> simplified = GridRouter.simplify(Arrays.asList(raw.getCoordinates()), blocked);
                    return new RouteRun(GEOMETRY_FACTORY.createLineString(simplified.toArray(new Coordinate[0])),
                            vertices.size(), expanded);
                }
                for (Integer next : adjacency.get(current.current)) {
                    if (current.previous >= 0 && !legalTurn(vertices.get(current.previous),
                            vertices.get(current.current), vertices.get(next))) continue;
                    double cost = current.cost + vertices.get(current.current).distance(vertices.get(next));
                    GraphState candidate = new GraphState(current.current, next, cost,
                            cost + vertices.get(next).distance(vertices.get(1)), vertices.size());
                    if (cost + 1e-9 >= best.getOrDefault(candidate.key, Double.POSITIVE_INFINITY)) continue;
                    best.put(candidate.key, cost);
                    parent.put(candidate.key, current);
                    open.add(candidate);
                }
            }
            throw new IllegalStateException("Visibility graph found no route in the tested corridor");
        }

        private static LineString path(GraphState goal, Map<Integer, GraphState> parent, List<Coordinate> vertices) {
            List<Coordinate> reversed = new ArrayList<>();
            GraphState current = goal;
            while (current != null) {
                reversed.add(new Coordinate(vertices.get(current.current)));
                current = parent.get(current.key);
            }
            Collections.reverse(reversed);
            return GEOMETRY_FACTORY.createLineString(reversed.toArray(new Coordinate[0]));
        }

        private static String key(Coordinate coordinate) {
            return Math.round(coordinate.x * 1e6) + ":" + Math.round(coordinate.y * 1e6);
        }
    }

    private static final class GraphState {
        private final int previous;
        private final int current;
        private final double cost;
        private final double estimate;
        private final int key;
        private GraphState(int previous, int current, double cost, double estimate, int vertices) {
            this.previous = previous; this.current = current; this.cost = cost; this.estimate = estimate;
            this.key = (previous + 1) * vertices + current;
        }
    }

    private static final class GridRouter {
        private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
        private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};
        private final double minX;
        private final double minY;
        private final double step;
        private final int columns;
        private final int rows;

        private GridRouter(Envelope area, double step) {
            this.minX = area.getMinX(); this.minY = area.getMinY(); this.step = step;
            this.columns = (int) Math.floor(area.getWidth() / step + 1e-9) + 1;
            this.rows = (int) Math.floor(area.getHeight() / step + 1e-9) + 1;
        }

        private RouteRun route(Point start, Point goal, List<Geometry> blocked) {
            int startX = gridIndex(start.getX(), minX), startY = gridIndex(start.getY(), minY);
            int goalX = gridIndex(goal.getX(), minX), goalY = gridIndex(goal.getY(), minY);
            int startCell = startY * columns + startX, goalCell = goalY * columns + goalX;
            int states = columns * rows * 9;
            double[] best = new double[states];
            Arrays.fill(best, Double.POSITIVE_INFINITY);
            int[] parent = new int[states];
            Arrays.fill(parent, -1);
            int initial = startCell * 9;
            best[initial] = 0;
            PriorityQueue<GridState> open = new PriorityQueue<>(Comparator
                    .comparingDouble((GridState state) -> state.estimate).thenComparingDouble(state -> state.cost));
            open.add(new GridState(initial, 0, distance(startX, startY, goalX, goalY)));
            int expanded = 0, discovered = 1, goalState = -1;
            while (!open.isEmpty()) {
                GridState current = open.remove();
                if (current.cost > best[current.state] + 1e-9) continue;
                expanded++;
                int cell = current.state / 9;
                int x = cell % columns, y = cell / columns;
                if (cell == goalCell) { goalState = current.state; break; }
                int previousDirection = current.state % 9 - 1;
                Coordinate from = coordinate(x, y);
                for (int direction = 0; direction < DX.length; direction++) {
                    int nx = x + DX[direction], ny = y + DY[direction];
                    if (nx < 0 || nx >= columns || ny < 0 || ny >= rows) continue;
                    if (previousDirection >= 0 && DX[previousDirection] * DX[direction]
                            + DY[previousDirection] * DY[direction] < 0) continue;
                    Coordinate to = coordinate(nx, ny);
                    if (!clear(line(from, to), blocked)) continue;
                    int nextCell = ny * columns + nx;
                    int nextState = nextCell * 9 + direction + 1;
                    double nextCost = current.cost + from.distance(to);
                    if (nextCost + 1e-9 >= best[nextState]) continue;
                    if (Double.isInfinite(best[nextState])) discovered++;
                    best[nextState] = nextCost;
                    parent[nextState] = current.state;
                    open.add(new GridState(nextState, nextCost,
                            nextCost + distance(nx, ny, goalX, goalY) * step));
                }
            }
            if (goalState < 0) throw new IllegalStateException("Grid search found no route in the tested corridor");
            List<Coordinate> raw = new ArrayList<>();
            for (int state = goalState; ; state = parent[state]) {
                int cell = state / 9;
                raw.add(coordinate(cell % columns, cell / columns));
                if (state == initial) break;
            }
            Collections.reverse(raw);
            List<Coordinate> simplified = simplify(raw, blocked);
            return new RouteRun(GEOMETRY_FACTORY.createLineString(simplified.toArray(new Coordinate[0])), discovered, expanded);
        }

        private static List<Coordinate> simplify(List<Coordinate> raw, List<Geometry> blocked) {
            List<Coordinate> result = new ArrayList<>();
            int current = 0;
            result.add(raw.get(0));
            while (current < raw.size() - 1) {
                int next = current + 1;
                for (int candidate = raw.size() - 1; candidate > current + 1; candidate--) {
                    if (!clear(line(raw.get(current), raw.get(candidate)), blocked)) continue;
                    if (result.size() > 1 && !legalTurn(result.get(result.size() - 2),
                            raw.get(current), raw.get(candidate))) continue;
                    if (candidate < raw.size() - 1 && !legalTurn(raw.get(current), raw.get(candidate),
                            raw.get(candidate + 1))) continue;
                    next = candidate;
                    break;
                }
                result.add(raw.get(next));
                current = next;
            }
            return result;
        }

        private Coordinate coordinate(int x, int y) { return new Coordinate(minX + x * step, minY + y * step); }
        private int gridIndex(double coordinate, double origin) {
            int index = (int) Math.round((coordinate - origin) / step);
            if (Math.abs(origin + index * step - coordinate) > 1e-8) {
                throw new IllegalArgumentException("Benchmark endpoints must align with the grid");
            }
            return index;
        }
        private static double distance(int x1, int y1, int x2, int y2) {
            return Math.hypot(x2 - x1, y2 - y1);
        }
    }

    private static final class GridState {
        private final int state;
        private final double cost;
        private final double estimate;
        private GridState(int state, double cost, double estimate) {
            this.state = state; this.cost = cost; this.estimate = estimate;
        }
    }
}
