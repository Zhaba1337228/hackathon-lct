package ru.lct.heatnetwork.spatial;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.rules.RestrictionRule;
import ru.lct.heatnetwork.rules.RuleCatalog;

/** Distance checks use the pipe-pair and obstacle envelopes, with only matching approved passages exempted. */
public final class ClearanceCheck {
    private static final double EQUALITY_TOLERANCE_M = 1e-9;
    private static final double EXEMPTION_ROUNDING_LIMIT_M = 1e-6;
    private static final double EXEMPTION_STABILITY_GUARD_M = 1e-5;

    public double requiredCenterlineDistanceM(String restrictionType, int diameterMm,
            BigDecimal pipePairWidthM, Double obstacleWidthM) {
        RestrictionRule rule = RuleCatalog.restriction(restrictionType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown restriction type: " + restrictionType));
        double pipeRadius = positive(pipePairWidthM, "pipePairWidthM").doubleValue() / 2.0;
        double obstacleRadius = obstacleWidthM == null ? 0 : positive(obstacleWidthM, "obstacleWidthM") / 2.0;
        return rule.minimumClearanceM(diameterMm).doubleValue() + pipeRadius + obstacleRadius;
    }

    public boolean respectsClearance(LineString route, Geometry obstacle, double requiredCenterlineDistanceM,
            TypedId obstacleId, List<? extends ClearanceRange> passages) {
        if (route == null || obstacle == null || !Double.isFinite(requiredCenterlineDistanceM)
                || requiredCenterlineDistanceM < 0) throw new IllegalArgumentException("Invalid clearance input");
        if (route.getSRID() != 32637 || obstacle.getSRID() != 32637) {
            throw new IllegalArgumentException("Clearance geometries must use EPSG:32637");
        }
        if (obstacleId == null || passages == null || passages.isEmpty()) {
            return atLeast(route, obstacle, requiredCenterlineDistanceM);
        }
        List<ClearanceRange> matching = new ArrayList<>();
        for (ClearanceRange passage : passages) {
            boolean matches = passage instanceof IntersectionSegment
                    ? ((IntersectionSegment) passage).getRestrictionIds().contains(obstacleId)
                    : obstacleId.equals(passage.getRestrictionId());
            if (matches) matching.add(passage);
        }
        matching.sort(Comparator.comparingDouble(ClearanceRange::getStartMeasureM));
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        double cursor = 0;
        for (ClearanceRange passage : matching) {
            double start = Math.max(cursor, Math.min(route.getLength(), passage.getStartMeasureM()));
            double end = Math.max(start, Math.min(route.getLength(), passage.getEndMeasureM()));
            if (start - cursor > EQUALITY_TOLERANCE_M) {
                LineString unexempt = (LineString) indexed.extractLine(cursor, start);
                if (!atLeast(unexempt, obstacle, requiredCenterlineDistanceM)) {
                    return false;
                }
            }
            cursor = Math.max(cursor, end);
        }
        if (route.getLength() - cursor <= EQUALITY_TOLERANCE_M) return true;
        LineString unexempt = (LineString) indexed.extractLine(cursor, route.getLength());
        return atLeast(unexempt, obstacle, requiredCenterlineDistanceM);
    }

    /** Rechecks a mapped own-OKS exemption on the exact segment geometry used by the validator. */
    public double stabilizeExemptionStart(LineString route, Geometry obstacle,
            double requiredCenterlineDistanceM, double startMeasureM) {
        if (route == null || obstacle == null || route.getSRID() != 32637 || obstacle.getSRID() != 32637
                || !Double.isFinite(requiredCenterlineDistanceM) || requiredCenterlineDistanceM < 0
                || !Double.isFinite(startMeasureM) || startMeasureM < 0
                || startMeasureM > route.getLength()) {
            throw new IllegalArgumentException("Invalid mapped own-OKS exemption");
        }
        if (startMeasureM == 0) return 0;
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        LineString prefix = (LineString) indexed.extractLine(0, startMeasureM);
        double measured = prefix.distance(obstacle);
        if (measured + EQUALITY_TOLERANCE_M >= requiredCenterlineDistanceM
                || requiredCenterlineDistanceM - measured > EXEMPTION_ROUNDING_LIMIT_M) {
            return startMeasureM;
        }
        double guardedStart = Math.max(0, startMeasureM - EXEMPTION_STABILITY_GUARD_M);
        LineString guardedPrefix = (LineString) indexed.extractLine(0, guardedStart);
        return atLeast(guardedPrefix, obstacle, requiredCenterlineDistanceM)
                ? guardedStart : startMeasureM;
    }

    /** Returns only the own-building final approach plus its outside clearance buffer. */
    public ClearanceExemption ownOksFinalApproach(LineString route, Geometry ownOks, Point target,
            TypedId ownOksId, int diameterMm) {
        if (route == null || ownOks == null || target == null || ownOksId == null
                || route.getSRID() != 32637 || ownOks.getSRID() != 32637 || target.getSRID() != 32637) {
            throw new IllegalArgumentException("Own-OKS approach geometries must use EPSG:32637");
        }
        if (!ownOks.contains(target)) throw new IllegalArgumentException("Target must be inside its own OKS polygon");
        if (route.getEndPoint().distance(target) > 0.25) {
            throw new IllegalArgumentException("Final route endpoint must coincide with target within 0.25 m");
        }
        double pipeRadius = RuleCatalog.diameter(diameterMm)
                .orElseThrow(() -> new IllegalArgumentException("Unknown pipe diameter: " + diameterMm))
                .getPairWidthM().doubleValue() / 2.0;
        double clearance = RuleCatalog.restriction("oks").get().minimumClearanceM(diameterMm).doubleValue();
        CoordinatePoint boundary = nearestBoundary(route, ownOks, target);
        double routeLength = route.getLength();
        if (boundary.measure >= routeLength - EQUALITY_TOLERANCE_M) {
            throw new IllegalArgumentException("The target must be reached from outside its own OKS");
        }
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        LineString boundaryApproach = (LineString) indexed.extractLine(boundary.measure, routeLength);
        if (!isStraight(boundaryApproach)
                || route.getFactory().createPoint(boundary.point).distance(boundaryApproach) > 0.25) {
            throw new IllegalArgumentException("The final approach must be straight from the nearest OKS boundary");
        }
        double start = restoreClearanceBefore(route, ownOks, boundary.measure, clearance + pipeRadius);
        LineString outsideApproach = (LineString) indexed.extractLine(start, boundary.measure);
        Geometry reentry = outsideApproach.intersection(ownOks);
        for (org.locationtech.jts.geom.Coordinate point : reentry.getCoordinates()) {
            if (point.distance(boundary.point) > 0.25) {
                throw new IllegalArgumentException(
                        "The final approach crosses its own OKS again before the nearest boundary");
            }
        }
        LineString completeApproach = (LineString) indexed.extractLine(start, routeLength);
        if (!isStraight(completeApproach)) {
            throw new IllegalArgumentException("The final approach must remain straight until the required clearance is restored");
        }
        return new ClearanceExemption(ownOksId, start, routeLength);
    }

    private static double restoreClearanceBefore(LineString route, Geometry obstacle, double boundaryMeasure,
            double requiredDistance) {
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        double current = boundaryMeasure;
        while (current > 0) {
            double next = Math.max(0, current - 0.25);
            if (route.getFactory().createPoint(indexed.extractPoint(next)).distance(obstacle)
                    + EQUALITY_TOLERANCE_M >= requiredDistance) {
                double violating = current, clear = next;
                for (int i = 0; i < 40; i++) {
                    double middle = (violating + clear) / 2.0;
                    if (route.getFactory().createPoint(indexed.extractPoint(middle)).distance(obstacle)
                            + EQUALITY_TOLERANCE_M >= requiredDistance) clear = middle;
                    else violating = middle;
                }
                return clear;
            }
            current = next;
        }
        throw new IllegalArgumentException("The final approach has no compliant point before the OKS boundary");
    }

    private static CoordinatePoint nearestBoundary(LineString route, Geometry ownOks, Point target) {
        Geometry boundary = ownOks.getBoundary();
        org.locationtech.jts.geom.Coordinate nearest = DistanceOp.nearestPoints(target, boundary)[1];
        LengthIndexedLine routeIndex = new LengthIndexedLine(route);
        double measure = routeIndex.project(nearest);
        org.locationtech.jts.geom.Coordinate projected = routeIndex.extractPoint(measure);
        if (projected.distance(nearest) > 0.25) {
            throw new IllegalArgumentException("Final route must leave its own OKS at the nearest boundary point");
        }
        return new CoordinatePoint(nearest, measure);
    }

    private static boolean isStraight(LineString line) {
        if (line.getLength() <= EQUALITY_TOLERANCE_M) return false;
        org.locationtech.jts.geom.Coordinate first = line.getCoordinateN(0);
        org.locationtech.jts.geom.Coordinate last = line.getCoordinateN(line.getNumPoints() - 1);
        org.locationtech.jts.geom.LineSegment chord = new org.locationtech.jts.geom.LineSegment(first, last);
        for (org.locationtech.jts.geom.Coordinate point : line.getCoordinates()) {
            if (chord.distance(point) > 0.01) return false;
        }
        return true;
    }

    private static final class CoordinatePoint {
        private final org.locationtech.jts.geom.Coordinate point;
        private final double measure;
        private CoordinatePoint(org.locationtech.jts.geom.Coordinate point, double measure) {
            this.point = point; this.measure = measure;
        }
    }

    private static boolean atLeast(Geometry route, Geometry obstacle, double threshold) {
        return route.distance(obstacle) + EQUALITY_TOLERANCE_M >= threshold;
    }

    private static BigDecimal positive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static double positive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
