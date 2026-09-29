package ru.lct.heatnetwork.spatial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.math.BigDecimal;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.rules.RestrictionRule;
import ru.lct.heatnetwork.rules.RuleCatalog;

/** Builds straight crossing windows and splits a route whenever the active restriction set changes. */
public final class SpecialCrossingBuilder {
    private static final double NODE_EPSILON_M = 1e-6;
    private static final double STRAIGHT_TOLERANCE_M = 0.01;
    private static final double ANGLE_TOLERANCE_DEGREES = 1e-8;
    private static final Set<String> POLYGON_CROSSINGS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("road", "tram_tracks")));
    private static final Set<String> LINE_CROSSINGS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("gas_pipeline", "power_cable", "heat_network")));

    public List<IntersectionSegment> build(LineString route, List<CrossingRestriction> restrictions,
            int diameterMm, boolean routeStartIsNode, boolean routeEndIsNode) {
        requireProjected(route);
        if (route.getLength() <= 0) throw new IllegalArgumentException("route must have positive length");
        double pipeHalfWidth = RuleCatalog.diameter(diameterMm)
                .orElseThrow(() -> new IllegalArgumentException("Unknown pipe diameter: " + diameterMm))
                .getPairWidthM().doubleValue() / 2.0;
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        List<Passage> passages = new ArrayList<>();
        for (CrossingRestriction crossing : restrictions) {
            RestrictionRule rule = RuleCatalog.restriction(crossing.type)
                    .orElseThrow(() -> new InvalidSpecialCrossingException("Unknown restriction type: " + crossing.type));
            if (rule.getCrossing() != RestrictionRule.Crossing.SPECIAL_PASSAGE) {
                throw new InvalidSpecialCrossingException("Restriction does not permit a special passage: " + crossing.type);
            }
            if (POLYGON_CROSSINGS.contains(crossing.type)) {
                collectPolygonPassages(route, indexed, crossing, diameterMm, pipeHalfWidth,
                        routeStartIsNode, routeEndIsNode, passages);
            } else if (LINE_CROSSINGS.contains(crossing.type)) {
                collectLinePassages(route, indexed, crossing, diameterMm, pipeHalfWidth,
                        routeStartIsNode, routeEndIsNode, passages);
            } else {
                throw new InvalidSpecialCrossingException("Unsupported crossing geometry: " + crossing.type);
            }
        }
        return partition(route, indexed, passages, routeStartIsNode, routeEndIsNode);
    }

    private static void collectPolygonPassages(LineString route, LengthIndexedLine indexed,
            CrossingRestriction crossing, int diameterMm, double pipeHalfWidth,
            boolean routeStartIsNode, boolean routeEndIsNode, List<Passage> passages) {
        Geometry polygonal = crossing.geometry;
        if (polygonal instanceof LineString || crossing.geometry.getDimension() == 1) {
            if (crossing.widthM == null || !Double.isFinite(crossing.widthM) || crossing.widthM <= 0) {
                throw new InvalidSpecialCrossingException("Linear road/tram restriction requires positive width_m");
            }
            polygonal = crossing.geometry.buffer(crossing.widthM / 2.0);
        }
        if (polygonal.getDimension() != 2) {
            throw new InvalidSpecialCrossingException("Road/tram special passage requires polygon area or declared line width");
        }
        Geometry clipped = route.intersection(polygonal);
        List<LineString> insideParts = new ArrayList<>();
        collectLines(clipped, insideParts);
        LengthIndexedLine boundary = new LengthIndexedLine(polygonal.getBoundary());
        for (LineString inside : insideParts) {
            if (inside.getLength() <= NODE_EPSILON_M) continue;
            Coordinate first = inside.getCoordinateN(0);
            Coordinate last = inside.getCoordinateN(inside.getNumPoints() - 1);
            double entry = indexed.project(first);
            double exit = indexed.project(last);
            double start = Math.min(entry, exit);
            double end = Math.max(entry, exit);
            if (end - start <= NODE_EPSILON_M) continue;
            if (crossingAngleDegrees(route, indexed, boundary, start) + ANGLE_TOLERANCE_DEGREES < 45
                    || crossingAngleDegrees(route, indexed, boundary, end) + ANGLE_TOLERANCE_DEGREES < 45) {
                throw new InvalidSpecialCrossingException("Road/tram crossing angle must be at least 45 degrees");
            }
            double passageStart = Math.max(0, start - 3.0);
            double passageEnd = Math.min(route.getLength(), end + 3.0);
            double requiredClearance = RuleCatalog.restriction(crossing.type).get()
                    .minimumClearanceM(diameterMm).doubleValue() + pipeHalfWidth;
            double[] extended = extendToClearance(route, indexed, polygonal, passageStart, passageEnd,
                    requiredClearance, routeStartIsNode, routeEndIsNode, crossing.type);
            passageStart = extended[0];
            passageEnd = extended[1];
            requireStraight(indexed, passageStart, passageEnd, crossing.type);
            passages.add(new Passage(crossing.id, crossing.type, passageStart, passageEnd,
                    RuleCatalog.restriction(crossing.type).get().getSpecialMultiplier()));
        }
    }

    private static void collectLinePassages(LineString route, LengthIndexedLine indexed,
            CrossingRestriction crossing, int diameterMm, double pipeHalfWidth,
            boolean routeStartIsNode, boolean routeEndIsNode, List<Passage> passages) {
        double width = obstacleWidth(crossing);
        Geometry bufferedObstacle = crossing.geometry.buffer(width / 2.0);
        Geometry intersections = route.intersection(crossing.geometry);
        RestrictionRule rule = RuleCatalog.restriction(crossing.type).get();
        double requiredClearance = rule.minimumClearanceM(diameterMm).doubleValue() + pipeHalfWidth;
        List<LineString> overlapParts = new ArrayList<>();
        collectLines(intersections, overlapParts);
        for (LineString overlap : overlapParts) {
            if (overlap.getLength() > NODE_EPSILON_M) {
                double a = indexed.project(overlap.getCoordinateN(0));
                double b = indexed.project(overlap.getCoordinateN(overlap.getNumPoints() - 1));
                addLinePassage(route, indexed, crossing, bufferedObstacle, requiredClearance,
                        routeStartIsNode, routeEndIsNode, passages, Math.min(a, b), Math.max(a, b));
            }
        }
        for (Coordinate point : intersections.getCoordinates()) {
            if (intersections.getDimension() == 1) break;
            double measure = indexed.project(point);
            addLinePassage(route, indexed, crossing, bufferedObstacle, requiredClearance,
                    routeStartIsNode, routeEndIsNode, passages, measure, measure);
        }
    }

    private static void addLinePassage(LineString route, LengthIndexedLine indexed, CrossingRestriction crossing,
            Geometry bufferedObstacle, double requiredClearance, boolean routeStartIsNode, boolean routeEndIsNode,
            List<Passage> passages, double start, double end) {
        double passageStart = Math.max(0, start - 2.0);
        double passageEnd = Math.min(route.getLength(), end + 2.0);
        if (passageEnd - passageStart <= NODE_EPSILON_M) return;
        double[] extended = extendToClearance(route, indexed, bufferedObstacle, passageStart, passageEnd,
                requiredClearance, routeStartIsNode, routeEndIsNode, crossing.type);
        passageStart = extended[0];
        passageEnd = extended[1];
        requireStraight(indexed, passageStart, passageEnd, crossing.type);
        passages.add(new Passage(crossing.id, crossing.type, passageStart, passageEnd,
                RuleCatalog.restriction(crossing.type).get().getSpecialMultiplier()));
    }

    private static double[] extendToClearance(LineString route, LengthIndexedLine indexed, Geometry obstacle,
            double start, double end, double requiredDistance, boolean startIsNode, boolean endIsNode, String type) {
        double extendedStart = extendBoundary(route, indexed, obstacle, start, -1, requiredDistance, startIsNode, type);
        double extendedEnd = extendBoundary(route, indexed, obstacle, end, 1, requiredDistance, endIsNode, type);
        return new double[] {extendedStart, extendedEnd};
    }

    private static double extendBoundary(LineString route, LengthIndexedLine indexed, Geometry obstacle,
            double boundary, int direction, double requiredDistance, boolean endpointIsNode, String type) {
        double length = route.getLength();
        if (clearAt(route, indexed, obstacle, boundary, requiredDistance)) return boundary;
        double current = boundary;
        while (direction < 0 ? current > 0 : current < length) {
            double next = direction < 0 ? Math.max(0, current - 0.25) : Math.min(length, current + 0.25);
            if (clearAt(route, indexed, obstacle, next, requiredDistance)) {
                double violating = current, clear = next;
                for (int i = 0; i < 40; i++) {
                    double middle = (violating + clear) / 2.0;
                    if (clearAt(route, indexed, obstacle, middle, requiredDistance)) clear = middle;
                    else violating = middle;
                }
                return clear;
            }
            current = next;
        }
        throw new InvalidSpecialCrossingException("Clearance cannot be restored before route endpoint for " + type);
    }

    private static boolean clearAt(LineString line, LengthIndexedLine route, Geometry obstacle,
            double measure, double requiredDistance) {
        return line.getFactory().createPoint(route.extractPoint(measure)).distance(obstacle)
                + NODE_EPSILON_M >= requiredDistance;
    }

    private static double obstacleWidth(CrossingRestriction crossing) {
        if (crossing.widthM != null) return crossing.widthM;
        if ("gas_pipeline".equals(crossing.type)) return 0.40;
        if ("power_cable".equals(crossing.type)) return 0.20;
        throw new InvalidSpecialCrossingException("Existing heat_network crossing requires its tabulated pair width");
    }

    private static List<IntersectionSegment> partition(LineString route, LengthIndexedLine indexed,
            List<Passage> passages, boolean routeStartIsNode, boolean routeEndIsNode) {
        TreeSet<Double> breaks = new TreeSet<>();
        breaks.add(0.0);
        breaks.add(route.getLength());
        for (Passage passage : passages) {
            breaks.add(passage.start);
            breaks.add(passage.end);
        }
        List<Double> measures = new ArrayList<>(breaks);
        List<IntersectionSegment> result = new ArrayList<>();
        for (int i = 0; i + 1 < measures.size(); i++) {
            double start = measures.get(i), end = measures.get(i + 1);
            if (end - start <= NODE_EPSILON_M) continue;
            double midpoint = (start + end) / 2.0;
            Set<TypedId> activeIds = new LinkedHashSet<>();
            BigDecimal multiplier = RuleCatalog.baseMultiplier();
            for (Passage passage : passages) {
                if (midpoint >= passage.start && midpoint <= passage.end) {
                    activeIds.add(passage.id);
                    if (passage.multiplier.compareTo(multiplier) > 0) multiplier = passage.multiplier;
                }
            }
            LineString fragment = (LineString) indexed.extractLine(start, end);
            result.add(new IntersectionSegment(start, end, fragment, activeIds, multiplier,
                    start > NODE_EPSILON_M || !routeStartIsNode,
                    end < route.getLength() - NODE_EPSILON_M || !routeEndIsNode));
        }
        return Collections.unmodifiableList(result);
    }

    private static double crossingAngleDegrees(LineString route, LengthIndexedLine routeIndex,
            LengthIndexedLine boundaryIndex, double routeMeasure) {
        double routeLength = route.getLength();
        Coordinate routeBefore = routeIndex.extractPoint(Math.max(0, routeMeasure - 0.5));
        Coordinate routeAfter = routeIndex.extractPoint(Math.min(routeLength, routeMeasure + 0.5));
        if (routeBefore.equals2D(routeAfter)) return 0;
        Point routePoint = route.getFactory().createPoint(routeIndex.extractPoint(routeMeasure));
        Geometry boundaryGeometry = boundaryIndex.extractLine(0, Math.max(0.001, boundaryIndex.getEndIndex()));
        Coordinate nearestBoundary = DistanceOp.nearestPoints(routePoint, boundaryGeometry)[1];
        double boundaryMeasure = boundaryIndex.project(nearestBoundary);
        double boundaryLength = boundaryIndex.getEndIndex();
        Coordinate boundaryBefore = boundaryIndex.extractPoint(Math.max(0, boundaryMeasure - 0.5));
        Coordinate boundaryAfter = boundaryIndex.extractPoint(Math.min(boundaryLength, boundaryMeasure + 0.5));
        if (boundaryBefore.equals2D(boundaryAfter)) return 0;
        double rx = routeAfter.x - routeBefore.x, ry = routeAfter.y - routeBefore.y;
        double bx = boundaryAfter.x - boundaryBefore.x, by = boundaryAfter.y - boundaryBefore.y;
        double degrees = Math.toDegrees(Math.atan2(Math.abs(rx * by - ry * bx), Math.abs(rx * bx + ry * by)));
        return Math.min(degrees, 180.0 - degrees);
    }

    private static void requireStraight(LengthIndexedLine route, double start, double end, String type) {
        LineString fragment = (LineString) route.extractLine(start, end);
        Coordinate first = fragment.getCoordinateN(0), last = fragment.getCoordinateN(fragment.getNumPoints() - 1);
        org.locationtech.jts.geom.LineSegment chord = new org.locationtech.jts.geom.LineSegment(first, last);
        for (Coordinate coordinate : fragment.getCoordinates()) {
            if (chord.distance(coordinate) > STRAIGHT_TOLERANCE_M) {
                throw new InvalidSpecialCrossingException("Special passage through " + type + " must be straight");
            }
        }
    }

    private static void collectLines(Geometry geometry, List<LineString> output) {
        if (geometry instanceof LineString) {
            output.add((LineString) geometry);
        } else if (geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) collectLines(geometry.getGeometryN(i), output);
        }
    }

    private static void requireProjected(Geometry geometry) {
        if (geometry == null || geometry.getSRID() != 32637) {
            throw new IllegalArgumentException("All crossing geometries must use EPSG:32637");
        }
    }

    public static final class CrossingRestriction {
        private final TypedId id;
        private final String type;
        private final Geometry geometry;
        private final Double widthM;

        public CrossingRestriction(TypedId id, String type, Geometry geometry, Double widthM) {
            if (id == null || type == null || geometry == null) throw new IllegalArgumentException("crossing fields are required");
            requireProjected(geometry);
            if (widthM != null && (!Double.isFinite(widthM) || widthM <= 0)) {
                throw new IllegalArgumentException("widthM must be positive");
            }
            this.id = id;
            this.type = type;
            this.geometry = geometry;
            this.widthM = widthM;
        }

        public TypedId getRestrictionId() { return id; }
        public String getType() { return type; }
        public Geometry getGeometry() { return geometry; }
        public Double getWidthM() { return widthM; }
    }

    private static final class Passage {
        private final TypedId id;
        private final String type;
        private final double start, end;
        private final BigDecimal multiplier;
        private Passage(TypedId id, String type, double start, double end, BigDecimal multiplier) {
            this.id = id; this.type = type; this.start = start; this.end = end; this.multiplier = multiplier;
        }
    }
}
