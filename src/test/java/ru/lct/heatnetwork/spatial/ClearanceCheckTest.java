package ru.lct.heatnetwork.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.projection.CrsTransform;

class ClearanceCheckTest {
    private final ClearanceCheck check = new ClearanceCheck();

    @Test
    void requiredAxisDistanceIncludesHalfOfBothDeclaredWidths() {
        assertEquals(2.45, check.requiredCenterlineDistanceM("gas_pipeline", 50,
                new BigDecimal("0.50"), 0.40), 1e-12);
        assertEquals(2.25, check.requiredCenterlineDistanceM("gas_pipeline", 50,
                new BigDecimal("0.50"), null), 1e-12);
    }

    @Test
    void equalityAtMinimumClearanceIsAllowedAndSmallerDistanceIsRejected() throws Exception {
        LineString atLimit = line("LINESTRING (0 1.3, 10 1.3)");
        LineString insideLimit = line("LINESTRING (0 1.299999, 10 1.299999)");
        Polygon park = polygon("POLYGON ((4 0, 6 0, 6 0.1, 4 0.1, 4 0))");
        double required = check.requiredCenterlineDistanceM("park", 50,
                RuleCatalog.diameter(50).get().getPairWidthM(), null);

        assertTrue(check.respectsClearance(atLimit, park, required, null, Collections.emptyList()));
        assertFalse(check.respectsClearance(insideLimit, park, required, null, Collections.emptyList()));
    }

    @Test
    void ownOksExemptionStartsAtClearanceRecoveryAndAppliesOnlyToItsBuilding() throws Exception {
        LineString route = line("LINESTRING (0 0, 15 0)");
        Polygon building = polygon("POLYGON ((10 -8, 24 -8, 24 8, 10 8, 10 -8))");
        Point target = (Point) projected("POINT (15 0)");
        TypedId ownId = TypedId.of("own");
        ClearanceExemption exemption = check.ownOksFinalApproach(route, building, target, ownId, 50);
        double required = RuleCatalog.restriction("oks").get().minimumClearanceM(50).doubleValue()
                + RuleCatalog.diameter(50).get().getPairWidthM().doubleValue() / 2.0;

        assertEquals(10.0 - required, exemption.getStartMeasureM(), 0.25);
        assertEquals(route.getLength(), exemption.getEndMeasureM(), 1e-9);
        assertTrue(check.respectsClearance(route, building, required, ownId, Collections.singletonList(exemption)));
        assertFalse(check.respectsClearance(route, building, required, ownId, Collections.emptyList()));
        assertFalse(check.respectsClearance(route, building, required, TypedId.of("other"),
                Collections.singletonList(exemption)));
    }

    @Test
    void ownOksExemptionRejectsBentFinalApproachAndWrongTarget() throws Exception {
        Polygon building = polygon("POLYGON ((10 -8, 24 -8, 24 8, 10 8, 10 -8))");
        Point target = (Point) projected("POINT (15 0)");
        LineString bent = line("LINESTRING (0 0, 10 0, 12 2, 15 0)");
        assertThrows(IllegalArgumentException.class,
                () -> check.ownOksFinalApproach(bent, building, target, TypedId.of("own"), 50));
        assertThrows(IllegalArgumentException.class,
                () -> check.ownOksFinalApproach(line("LINESTRING (0 0, 5 0)"), building,
                        (Point) projected("POINT (5 0)"), TypedId.of("own"), 50));
    }

    @Test
    void ownOksExemptionLeavesExtractedPrefixOutsideRequiredClearance() throws Exception {
        // The real 7-to-8 branch: the old point-based boundary rounded up by 1.8 nm
        // when the non-exempt line was extracted for final validation.
        LineString route = line("LINESTRING (414410.42291728355 6173293.791363468, "
                + "414410.34614716744 6173294.264204449, "
                + "414414.2920705995 6173304.041658609)");
        Geometry ownOks = new CrsTransform().toProjected(new WKTReader().read(
                "MULTIPOLYGON (((37.639066225 55.699631138, 37.639171751 55.699608474, "
                + "37.639178147 55.699618078, 37.63916146 55.699621689, "
                + "37.639213262 55.699698426, 37.639609459 55.69961319, "
                + "37.638436748 55.697873197, 37.638147995 55.697935316, "
                + "37.638161424 55.697955241, 37.638138063 55.697960297, "
                + "37.638126073 55.697942706, 37.63753871 55.698069018, "
                + "37.638652387 55.699720254, 37.639066225 55.699631138)))"));
        TypedId ownId = TypedId.of("own");
        double required = check.requiredCenterlineDistanceM("oks", 125,
                RuleCatalog.diameter(125).get().getPairWidthM(), null);
        Point target = (Point) projected("POINT (414414.2920705995 6173304.041658609)");
        assertEquals(5.3, required, 1e-12);
        ClearanceExemption exemption = check.ownOksFinalApproach(route, ownOks,
                target, ownId, 125);
        assertTrue(check.respectsClearance(route, ownOks, required, ownId,
                Collections.singletonList(exemption)));
        double mappedStart = 0.5290326136364953;
        assertFalse(check.respectsClearance(route, ownOks, required, ownId,
                Collections.singletonList(new ClearanceExemption(ownId,
                        mappedStart, route.getLength()))));

        double stableStart = check.stabilizeExemptionStart(route, ownOks, required, mappedStart);
        LineString nonExempt = (LineString) new LengthIndexedLine(route).extractLine(0, stableStart);
        assertTrue(stableStart < mappedStart && mappedStart - stableStart <= 1e-4);
        assertTrue(nonExempt.distance(ownOks) + 1e-9 >= required);
        assertTrue(check.respectsClearance(route, ownOks, required, ownId,
                Collections.singletonList(new ClearanceExemption(ownId,
                        stableStart, route.getLength()))));
        assertEquals(2.0, check.stabilizeExemptionStart(route, ownOks, required, 2.0), 0.0,
                "a material violation must remain rejected");
    }

    @Test
    void nonNodeRouteEndpointsAreMarkedAsTechnicalNodes() throws Exception {
        LineString route = line("LINESTRING (0 0, 20 0)");
        Polygon road = polygon("POLYGON ((8 -5, 12 -5, 12 5, 8 5, 8 -5))");
        java.util.List<IntersectionSegment> segments = new SpecialCrossingBuilder().build(route,
                Collections.singletonList(new SpecialCrossingBuilder.CrossingRestriction(
                        TypedId.of("road"), "road", road, null)), 50, false, false);
        assertTrue(segments.get(0).isStartTechnicalNode());
        assertTrue(segments.get(segments.size() - 1).isEndTechnicalNode());
    }

    @Test
    void ownApproachCannotExemptAnotherWingBeforeTheNearestFacade() throws Exception {
        Geometry concave = projected("POLYGON ((0 0, 10 0, 10 10, 7 10, 7 3, 3 3, 3 10, 0 10, 0 0))");
        Geometry separateParts = projected("MULTIPOLYGON (((0 0, 3 0, 3 10, 0 10, 0 0)), "
                + "((7 0, 10 0, 10 10, 7 10, 7 0)))");
        for (Geometry building : new Geometry[] {concave, separateParts}) {
            assertThrows(IllegalArgumentException.class, () -> check.ownOksFinalApproach(
                    line("LINESTRING (20 6, 2 6)"), building, (Point) projected("POINT (2 6)"),
                    TypedId.of("own"), 50), "A straight line must not tunnel through another wing");
        }
        Geometry wideCourtyard = projected("POLYGON ((0 0, 30 0, 30 20, 27 20, 27 1, 3 1, 3 20, 0 20, 0 0))");
        LineString valid = line("LINESTRING (15 11, 10 8, 2 8)");
        ClearanceExemption approach = check.ownOksFinalApproach(valid, wideCourtyard,
                (Point) projected("POINT (2 8)"), TypedId.of("own"), 50);
        assertTrue(approach.getStartMeasureM() > 0, "A normal short own-building entry remains valid");
    }

    private static LineString line(String wkt) throws Exception { return (LineString) projected(wkt); }
    private static Polygon polygon(String wkt) throws Exception { return (Polygon) projected(wkt); }
    private static Geometry projected(String wkt) throws Exception {
        Geometry geometry = new WKTReader().read(wkt);
        geometry.setSRID(32637);
        return geometry;
    }
}
