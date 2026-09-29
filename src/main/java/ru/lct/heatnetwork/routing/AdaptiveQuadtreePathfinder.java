package ru.lct.heatnetwork.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Experimental finite-resolution route search. Cells only propose transitions;
 * every graph edge is checked by the caller's exact engineering predicate.
 */
final class AdaptiveQuadtreePathfinder {
    private static final double EPSILON = 1e-8;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_ATTACHMENT_CELLS = 32;

    SearchResult search(Geometry area, Coordinate start, Coordinate goal, GeometryFactory factory,
            List<Geometry> blockedRegions, List<Geometry> refinementRegions, List<Geometry> specialRegions,
            int maximumCells, int maximumEdgeChecks, double minimumCellSizeM,
            Predicate<LineString> edgeAllowed, TurnRule turnAllowed) {
        if (area == null || start == null || goal == null || factory == null || blockedRegions == null
                || refinementRegions == null || specialRegions == null || maximumCells < 4
                || maximumEdgeChecks < 1 || !Double.isFinite(minimumCellSizeM) || minimumCellSizeM <= 0
                || edgeAllowed == null || turnAllowed == null) {
            throw new IllegalArgumentException("Adaptive search requires valid geometry, budgets and exact validators");
        }
        Envelope bounds = area.getEnvelopeInternal();
        double side = Math.max(bounds.getWidth(), bounds.getHeight());
        if (side <= EPSILON) return SearchResult.incomplete(0, 0, 0, 0, 0, "Search area is degenerate");
        double half = side / 2.0;
        double cx = bounds.centre().x;
        double cy = bounds.centre().y;
        Cell root = new Cell(cx - half, cy - half, cx + half, cy + half, 0);
        Budget budget = new Budget(maximumCells, maximumEdgeChecks);
        budget.cellsCreated = 1;

        ShapeIndex blockers = new ShapeIndex(blockedRegions);
        ShapeIndex refinements = new ShapeIndex(refinementRegions);
        ShapeIndex specials = new ShapeIndex(specialRegions);
        List<Cell> leaves = new ArrayList<>();
        refine(root, area, start, goal, factory, blockers, refinements, minimumCellSizeM,
                budget, leaves);
        balance(leaves, area, start, goal, factory, blockers, refinements,
                minimumCellSizeM, budget);
        int balanceViolations = balanceViolations(leaves);
        if (balanceViolations > 0) budget.incomplete = true;

        leaves.sort(Comparator.comparingDouble((Cell cell) -> cell.minX)
                .thenComparingDouble(cell -> cell.minY).thenComparingInt(cell -> cell.depth));
        Graph graph = new Graph(factory, area, blockers, budget, edgeAllowed);
        graph.addNode(start);
        int startIndex = 0;
        graph.addNode(goal);
        int goalIndex = 1;
        Map<Cell, Integer> centerByCell = new HashMap<>();
        List<IndexedCell> indexedCells = new ArrayList<>();
        STRtree cellIndex = new STRtree();
        int unresolvedCells = 0;
        for (Cell cell : leaves) {
            CellInfo info = inspect(cell, area, factory, blockers, refinements);
            if (info.blocked) continue;
            if (info.partialBlocked) unresolvedCells++;
            Coordinate center = cell.center();
            if (!area.covers(factory.createPoint(center)) || blockers.covers(center)) continue;
            int centerIndex = graph.addNode(center);
            centerByCell.put(cell, centerIndex);
            IndexedCell indexed = new IndexedCell(cell);
            indexedCells.add(indexed);
            cellIndex.insert(cell.envelope(), indexed);
        }
        cellIndex.build();

        connectEndpoint(graph, startIndex, start, cellIndex, minimumCellSizeM);
        connectEndpoint(graph, goalIndex, goal, cellIndex, minimumCellSizeM);

        STRtree leafIndex = new STRtree();
        for (Cell cell : leaves) leafIndex.insert(cell.envelope(), cell);
        leafIndex.build();
        Set<Long> adjacentPairs = new HashSet<>();
        for (Cell cell : leaves) {
            Integer firstCenter = centerByCell.get(cell);
            if (firstCenter == null) continue;
            @SuppressWarnings("unchecked")
            List<Cell> nearby = leafIndex.query(expanded(cell.envelope(), EPSILON * 4));
            for (Cell other : nearby) {
                if (cell == other) continue;
                Integer secondCenter = centerByCell.get(other);
                if (secondCenter == null) continue;
                if (!shareSide(cell, other)) continue;
                int low = Math.min(firstCenter, secondCenter);
                int high = Math.max(firstCenter, secondCenter);
                long pair = pairKey(low, high);
                if (!adjacentPairs.add(pair)) continue;
                Coordinate portal = portal(cell, other);
                int portalIndex = graph.addNode(portal);
                graph.addTransition(firstCenter, secondCenter);
                graph.addTransition(firstCenter, portalIndex);
                graph.addTransition(portalIndex, secondCenter);
            }
        }

        graph.addSpecialMacroTransitions(specials, minimumCellSizeM);
        if (unresolvedCells > 0 || budget.truncated) budget.incomplete = true;
        List<Coordinate> path = graph.shortestPath(startIndex, goalIndex, turnAllowed);
        boolean incomplete = budget.incomplete || unresolvedCells > 0;
        if (path == null) {
            String detail = incomplete
                    ? "Adaptive search exhausted a finite-resolution or work budget before ruling out a route"
                    : "No exact-valid route was found in the adaptive transition graph";
            return new SearchResult(incomplete ? Status.INCOMPLETE : Status.NO_PATH, null,
                    leaves.size(), graph.nodeCount(), graph.edgeCount(), budget.edgeChecks,
                    unresolvedCells, balanceViolations, detail);
        }
        LineString route = factory.createLineString(path.toArray(new Coordinate[0]));
        route.setSRID(32637);
        return new SearchResult(incomplete ? Status.INCOMPLETE : Status.FOUND, route,
                leaves.size(), graph.nodeCount(), graph.edgeCount(), budget.edgeChecks,
                unresolvedCells, balanceViolations,
                incomplete ? "Route is valid, but adaptive search was incomplete" : null);
    }

    private static void refine(Cell cell, Geometry area, Coordinate start, Coordinate goal,
            GeometryFactory factory, ShapeIndex blockers, ShapeIndex refinements,
            double minimumCellSizeM, Budget budget, List<Cell> leaves) {
        Geometry rectangle = factory.toGeometry(cell.envelope());
        if (!area.intersects(rectangle)) return;
        CellInfo info = inspect(cell, area, factory, blockers, refinements);
        if (info.blocked) {
            leaves.add(cell);
            return;
        }
        boolean endpoint = cell.envelope().covers(start) || cell.envelope().covers(goal);
        boolean needsDetail = endpoint || info.partialBlocked || info.refinementHit;
        if (!needsDetail || cell.size() <= minimumCellSizeM || cell.depth >= MAX_DEPTH) {
            if (needsDetail && (cell.size() <= minimumCellSizeM || cell.depth >= MAX_DEPTH)
                    && info.partialBlocked) budget.incomplete = true;
            leaves.add(cell);
            return;
        }
        if (budget.cellsCreated + 4 > budget.maximumCells) {
            budget.truncated = true;
            leaves.add(cell);
            return;
        }
        budget.cellsCreated += 4;
        for (Cell child : cell.children()) {
            refine(child, area, start, goal, factory, blockers, refinements,
                    minimumCellSizeM, budget, leaves);
        }
    }

    private static CellInfo inspect(Cell cell, Geometry area, GeometryFactory factory,
            ShapeIndex blockers, ShapeIndex refinements) {
        Geometry rectangle = factory.toGeometry(cell.envelope());
        boolean partial = false;
        boolean blocked = false;
        for (Geometry obstacle : blockers.intersecting(cell.envelope())) {
            if (obstacle.covers(rectangle)) {
                blocked = true;
                break;
            }
            partial = true;
        }
        boolean refinement = !refinements.intersecting(cell.envelope()).isEmpty();
        return new CellInfo(blocked, partial, refinement && area.intersects(rectangle));
    }

    private static void balance(List<Cell> leaves, Geometry area, Coordinate start, Coordinate goal,
            GeometryFactory factory, ShapeIndex blockers, ShapeIndex refinements,
            double minimumCellSizeM, Budget budget) {
        for (int pass = 0; pass < MAX_DEPTH; pass++) {
            STRtree index = new STRtree();
            for (Cell cell : leaves) index.insert(cell.envelope(), cell);
            index.build();
            Set<Cell> split = new HashSet<>();
            for (Cell cell : leaves) {
                @SuppressWarnings("unchecked")
                List<Cell> nearby = index.query(expanded(cell.envelope(), EPSILON * 4));
                for (Cell other : nearby) {
                    if (cell == other || !shareSide(cell, other)) continue;
                    Cell coarse = cell.size() > other.size() * 2.0 + EPSILON ? cell
                            : other.size() > cell.size() * 2.0 + EPSILON ? other : null;
                    if (coarse != null && coarse.size() > minimumCellSizeM && coarse.depth < MAX_DEPTH) {
                        split.add(coarse);
                    }
                }
            }
            if (split.isEmpty()) return;
            boolean changed = false;
            for (Cell parent : split) {
                if (!leaves.remove(parent)) continue;
                if (budget.cellsCreated + 4 > budget.maximumCells) {
                    budget.truncated = true;
                    leaves.add(parent);
                    continue;
                }
                budget.cellsCreated += 4;
                for (Cell child : parent.children()) {
                    refine(child, area, start, goal, factory, blockers, refinements,
                            minimumCellSizeM, budget, leaves);
                }
                changed = true;
            }
            if (!changed) return;
        }
        budget.incomplete = true;
    }

    private static void connectEndpoint(Graph graph, int endpointIndex, Coordinate endpoint,
            STRtree cellIndex, double minimumCellSizeM) {
        Envelope search = new Envelope(endpoint);
        search.expandBy(Math.max(minimumCellSizeM * 2.1, 0.25));
        @SuppressWarnings("unchecked")
        List<IndexedCell> candidates = cellIndex.query(search);
        candidates.sort(Comparator.<IndexedCell>comparingDouble(item -> item.cell.center().distance(endpoint))
                .thenComparingDouble(item -> item.cell.size())
                .thenComparingDouble(item -> item.cell.minX).thenComparingDouble(item -> item.cell.minY));
        int attempted = 0;
        for (IndexedCell candidate : candidates) {
            if (attempted++ >= MAX_ATTACHMENT_CELLS) break;
            graph.addTransition(endpointIndex, graph.findNode(candidate.cell.center()));
        }
    }

    private static boolean shareSide(Cell first, Cell second) {
        double epsilon = Math.max(EPSILON, Math.max(first.size(), second.size()) * 1e-10);
        double overlapX = Math.min(first.maxX, second.maxX) - Math.max(first.minX, second.minX);
        double overlapY = Math.min(first.maxY, second.maxY) - Math.max(first.minY, second.minY);
        boolean vertical = (Math.abs(first.maxX - second.minX) <= epsilon
                || Math.abs(second.maxX - first.minX) <= epsilon) && overlapY > epsilon;
        boolean horizontal = (Math.abs(first.maxY - second.minY) <= epsilon
                || Math.abs(second.maxY - first.minY) <= epsilon) && overlapX > epsilon;
        return vertical || horizontal;
    }

    private static int balanceViolations(List<Cell> leaves) {
        STRtree index = new STRtree();
        Map<Cell, Integer> indexes = new HashMap<>();
        for (int i = 0; i < leaves.size(); i++) {
            Cell cell = leaves.get(i);
            indexes.put(cell, i);
            index.insert(cell.envelope(), cell);
        }
        index.build();
        Set<Long> pairs = new HashSet<>();
        int violations = 0;
        for (int i = 0; i < leaves.size(); i++) {
            Cell cell = leaves.get(i);
            @SuppressWarnings("unchecked")
            List<Cell> nearby = index.query(expanded(cell.envelope(), EPSILON * 4));
            for (Cell other : nearby) {
                if (cell == other || !shareSide(cell, other)) continue;
                int otherIndex = indexes.get(other);
                long pair = pairKey(Math.min(i, otherIndex), Math.max(i, otherIndex));
                if (!pairs.add(pair)) continue;
                if (cell.depth > other.depth + 1 || other.depth > cell.depth + 1) violations++;
            }
        }
        return violations;
    }

    private static Coordinate portal(Cell first, Cell second) {
        double minX = Math.max(first.minX, second.minX);
        double maxX = Math.min(first.maxX, second.maxX);
        double minY = Math.max(first.minY, second.minY);
        double maxY = Math.min(first.maxY, second.maxY);
        if (Math.abs(first.maxX - second.minX) <= Math.max(EPSILON, first.size() * 1e-10)
                || Math.abs(second.maxX - first.minX) <= Math.max(EPSILON, second.size() * 1e-10)) {
            double x = Math.abs(first.maxX - second.minX) <= Math.max(EPSILON, first.size() * 1e-10)
                    ? (first.maxX + second.minX) / 2.0 : (second.maxX + first.minX) / 2.0;
            return new Coordinate(x, (minY + maxY) / 2.0);
        }
        double y = Math.abs(first.maxY - second.minY) <= Math.max(EPSILON, first.size() * 1e-10)
                ? (first.maxY + second.minY) / 2.0 : (second.maxY + first.minY) / 2.0;
        return new Coordinate((minX + maxX) / 2.0, y);
    }

    private static Envelope expanded(Envelope value, double amount) {
        Envelope result = new Envelope(value);
        result.expandBy(amount);
        return result;
    }

    private static long pairKey(int first, int second) {
        return (((long) first) << 32) ^ (second & 0xffffffffL);
    }

    enum Status { FOUND, NO_PATH, INCOMPLETE }

    interface TurnRule {
        boolean allowed(Coordinate previous, Coordinate current, Coordinate next);
    }

    static final class SearchResult {
        private final Status status;
        private final LineString route;
        private final int cells;
        private final int nodes;
        private final int edges;
        private final int edgeChecks;
        private final int unresolvedCells;
        private final int balanceViolations;
        private final String detail;

        private SearchResult(Status status, LineString route, int cells, int nodes, int edges,
                int edgeChecks, int unresolvedCells, int balanceViolations, String detail) {
            this.status = status;
            this.route = route;
            this.cells = cells;
            this.nodes = nodes;
            this.edges = edges;
            this.edgeChecks = edgeChecks;
            this.unresolvedCells = unresolvedCells;
            this.balanceViolations = balanceViolations;
            this.detail = detail;
        }
        private static SearchResult incomplete(int cells, int nodes, int edges, int checks,
                int unresolved, String detail) {
            return new SearchResult(Status.INCOMPLETE, null, cells, nodes, edges, checks, unresolved, 0, detail);
        }
        Status getStatus() { return status; }
        LineString getRoute() { return route; }
        int getCells() { return cells; }
        int getNodes() { return nodes; }
        int getEdges() { return edges; }
        int getEdgeChecks() { return edgeChecks; }
        int getUnresolvedCells() { return unresolvedCells; }
        int getBalanceViolations() { return balanceViolations; }
        String getDetail() { return detail; }
    }

    private static final class Cell {
        private final double minX;
        private final double minY;
        private final double maxX;
        private final double maxY;
        private final int depth;
        private Cell(double minX, double minY, double maxX, double maxY, int depth) {
            this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY; this.depth = depth;
        }
        private double size() { return Math.max(maxX - minX, maxY - minY); }
        private Coordinate center() { return new Coordinate((minX + maxX) / 2.0, (minY + maxY) / 2.0); }
        private Envelope envelope() { return new Envelope(minX, maxX, minY, maxY); }
        private List<Cell> children() {
            double midX = (minX + maxX) / 2.0;
            double midY = (minY + maxY) / 2.0;
            int nextDepth = depth + 1;
            List<Cell> children = new ArrayList<>(4);
            children.add(new Cell(minX, minY, midX, midY, nextDepth));
            children.add(new Cell(midX, minY, maxX, midY, nextDepth));
            children.add(new Cell(minX, midY, midX, maxY, nextDepth));
            children.add(new Cell(midX, midY, maxX, maxY, nextDepth));
            return children;
        }
    }

    private static final class CellInfo {
        private final boolean blocked;
        private final boolean partialBlocked;
        private final boolean refinementHit;
        private CellInfo(boolean blocked, boolean partialBlocked, boolean refinementHit) {
            this.blocked = blocked; this.partialBlocked = partialBlocked; this.refinementHit = refinementHit;
        }
    }

    private static final class IndexedCell {
        private final Cell cell;
        private IndexedCell(Cell cell) { this.cell = cell; }
    }

    private static final class IndexedShape {
        private final Geometry geometry;
        private IndexedShape(Geometry geometry) { this.geometry = geometry; }
    }

    private static final class ShapeIndex {
        private final STRtree tree = new STRtree();
        private final List<Geometry> geometries = new ArrayList<>();
        private ShapeIndex(List<Geometry> geometries) {
            for (Geometry geometry : geometries) {
                if (geometry != null && !geometry.isEmpty()) {
                    this.geometries.add(geometry);
                    tree.insert(geometry.getEnvelopeInternal(), new IndexedShape(geometry));
                }
            }
            tree.build();
        }
        @SuppressWarnings("unchecked")
        private List<Geometry> intersecting(Envelope envelope) {
            List<IndexedShape> values = tree.query(envelope);
            List<Geometry> result = new ArrayList<>(values.size());
            for (IndexedShape value : values) {
                if (value.geometry.getEnvelopeInternal().intersects(envelope)) result.add(value.geometry);
            }
            return result;
        }
        private boolean covers(Coordinate coordinate) {
            @SuppressWarnings("unchecked")
            List<IndexedShape> values = tree.query(new Envelope(coordinate));
            Point point = null;
            for (IndexedShape value : values) {
                if (point == null) point = value.geometry.getFactory().createPoint(coordinate);
                if (value.geometry.covers(point)) return true;
            }
            return false;
        }
        private List<Geometry> all() { return Collections.unmodifiableList(geometries); }
    }

    private static final class Budget {
        private final int maximumCells;
        private final int maximumEdgeChecks;
        private int cellsCreated;
        private int edgeChecks;
        private boolean truncated;
        private boolean incomplete;
        private Budget(int maximumCells, int maximumEdgeChecks) {
            this.maximumCells = maximumCells; this.maximumEdgeChecks = maximumEdgeChecks;
        }
    }

    private static final class Graph {
        private final GeometryFactory factory;
        private final Geometry area;
        private final ShapeIndex blockers;
        private final Budget budget;
        private final Predicate<LineString> edgeAllowed;
        private final List<Coordinate> nodes = new ArrayList<>();
        private final List<List<Edge>> adjacency = new ArrayList<>();
        private final Map<String, Integer> nodeIds = new HashMap<>();
        private final Set<Long> evaluatedPairs = new HashSet<>();
        private int allowedEdges;

        private Graph(GeometryFactory factory, Geometry area, ShapeIndex blockers,
                Budget budget, Predicate<LineString> edgeAllowed) {
            this.factory = factory; this.area = area; this.blockers = blockers;
            this.budget = budget; this.edgeAllowed = edgeAllowed;
        }
        private int addNode(Coordinate coordinate) {
            String key = key(coordinate);
            Integer existing = nodeIds.get(key);
            if (existing != null) return existing;
            int id = nodes.size();
            nodes.add(new Coordinate(coordinate));
            adjacency.add(new ArrayList<>());
            nodeIds.put(key, id);
            return id;
        }
        private int findNode(Coordinate coordinate) {
            Integer value = nodeIds.get(key(coordinate));
            if (value == null) throw new IllegalStateException("Adaptive cell center was not materialized");
            return value;
        }
        private void addTransition(int first, int second) {
            if (first == second) return;
            int low = Math.min(first, second);
            int high = Math.max(first, second);
            long pair = pairKey(low, high);
            if (!evaluatedPairs.add(pair)) return;
            if (budget.edgeChecks >= budget.maximumEdgeChecks) {
                budget.incomplete = true;
                return;
            }
            LineString line = factory.createLineString(new Coordinate[] { nodes.get(first), nodes.get(second) });
            if (line.getLength() <= EPSILON || !area.covers(line) || !edgeAllowed.test(line)) {
                budget.edgeChecks++;
                return;
            }
            budget.edgeChecks++;
            double length = line.getLength();
            adjacency.get(first).add(new Edge(second, length));
            adjacency.get(second).add(new Edge(first, length));
            allowedEdges++;
        }
        @SuppressWarnings("unchecked")
        private void addSpecialMacroTransitions(ShapeIndex specials, double minimumCellSizeM) {
            if (specials.tree.isEmpty()) return;
            STRtree nodeIndex = new STRtree();
            for (int i = 0; i < nodes.size(); i++) nodeIndex.insert(new Envelope(nodes.get(i)), i);
            nodeIndex.build();
            List<Geometry> specialGeometries = specials.all();
            for (Geometry special : specialGeometries) {
                Envelope candidateArea = expanded(special.getEnvelopeInternal(), Math.max(12.0, minimumCellSizeM * 8.0));
                List<?> rawNearby = nodeIndex.query(candidateArea);
                Map<Integer, List<Integer>> byDirection = new HashMap<>();
                Coordinate center = special.getCentroid().getCoordinate();
                for (Object value : rawNearby) {
                    int index = (Integer) value;
                    if (special.covers(factory.createPoint(nodes.get(index)))) continue;
                    Coordinate point = nodes.get(index);
                    double angle = Math.atan2(point.y - center.y, point.x - center.x);
                    int sector = Math.min(7, (int) Math.floor((angle + Math.PI) * 8.0 / (2.0 * Math.PI)));
                    byDirection.computeIfAbsent(sector, ignored -> new ArrayList<>()).add(index);
                }
                List<Integer> nearby = new ArrayList<>();
                for (int sector = 0; sector < 8; sector++) {
                    List<Integer> candidates = new ArrayList<>(
                            byDirection.getOrDefault(sector, Collections.emptyList()));
                    candidates.sort(Comparator.<Integer>comparingDouble(index ->
                            special.distance(factory.createPoint(nodes.get(index)))).reversed()
                            .thenComparingInt(index -> index));
                    nearby.addAll(candidates.subList(0, Math.min(16, candidates.size())));
                }
                for (int i = 0; i < nearby.size(); i++) {
                    for (int j = i + 1; j < nearby.size(); j++) {
                        int first = nearby.get(i);
                        int second = nearby.get(j);
                        if (special.covers(factory.createPoint(nodes.get(first)))
                                || special.covers(factory.createPoint(nodes.get(second)))) continue;
            LineString line = factory.createLineString(new Coordinate[] { nodes.get(first), nodes.get(second) });
            line.setSRID(32637);
                        if (!special.intersects(line)) continue;
                        addTransition(first, second);
                    }
                }
            }
            for (List<Edge> edges : adjacency) edges.sort(Comparator.comparingInt(edge -> edge.to));
        }
        private List<Coordinate> shortestPath(int start, int goal, TurnRule turnAllowed) {
            PriorityQueue<SearchState> open = new PriorityQueue<>(Comparator
                    .comparingDouble((SearchState state) -> state.estimate)
                    .thenComparingDouble(state -> state.cost)
                    .thenComparingInt(state -> state.current)
                    .thenComparingInt(state -> state.previous));
            Map<Long, Double> bestCost = new HashMap<>();
            Map<Long, Long> parent = new HashMap<>();
            SearchState initial = new SearchState(-1, start, 0.0, nodes.get(start).distance(nodes.get(goal)));
            open.add(initial);
            bestCost.put(initial.key, 0.0);
            long found = Long.MIN_VALUE;
            while (!open.isEmpty()) {
                SearchState current = open.remove();
                if (current.cost > bestCost.getOrDefault(current.key, Double.POSITIVE_INFINITY) + EPSILON) continue;
                if (current.current == goal) { found = current.key; break; }
                for (Edge edge : adjacency.get(current.current)) {
                    if (current.previous >= 0 && !turnAllowed.allowed(nodes.get(current.previous),
                            nodes.get(current.current), nodes.get(edge.to))) continue;
                    double cost = current.cost + edge.length;
                    SearchState next = new SearchState(current.current, edge.to, cost,
                            cost + nodes.get(edge.to).distance(nodes.get(goal)));
                    if (cost + EPSILON >= bestCost.getOrDefault(next.key, Double.POSITIVE_INFINITY)) continue;
                    bestCost.put(next.key, cost);
                    parent.put(next.key, current.key);
                    open.add(next);
                }
            }
            if (found == Long.MIN_VALUE) return null;
            List<Coordinate> reversed = new ArrayList<>();
            long stateKey = found;
            while (true) {
                int current = (int) stateKey;
                reversed.add(new Coordinate(nodes.get(current)));
                if (stateKey == initial.key) break;
                Long previous = parent.get(stateKey);
                if (previous == null) return null;
                stateKey = previous;
            }
            Collections.reverse(reversed);
            return reversed;
        }
        private int nodeCount() { return nodes.size(); }
        private int edgeCount() { return allowedEdges; }
        private static String key(Coordinate coordinate) {
            return Math.round(coordinate.x * 1_000_000.0) + ":" + Math.round(coordinate.y * 1_000_000.0);
        }
    }

    private static final class Edge {
        private final int to;
        private final double length;
        private Edge(int to, double length) { this.to = to; this.length = length; }
    }

    private static final class SearchState {
        private final int previous;
        private final int current;
        private final double cost;
        private final double estimate;
        private final long key;
        private SearchState(int previous, int current, double cost, double estimate) {
            this.previous = previous; this.current = current; this.cost = cost; this.estimate = estimate;
            this.key = ((((long) previous + 1L) & 0xffffffffL) << 32) | (current & 0xffffffffL);
        }
    }
}
