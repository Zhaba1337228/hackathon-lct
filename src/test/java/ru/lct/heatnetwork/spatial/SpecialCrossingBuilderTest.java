package ru.lct.heatnetwork.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnetwork.domain.TypedId;

class SpecialCrossingBuilderTest {
    private final SpecialCrossingBuilder builder = new SpecialCrossingBuilder();

    @Test
    void polygonCrossingGetsThreeMeterStraightPassageAndTechnicalNodes() throws Exception {
        LineString route = line("LINESTRING (0 0, 20 0)");
        Geometry road = polygon("POLYGON ((8 -5, 12 -5, 12 5, 8 5, 8 -5))");

        List<IntersectionSegment> segments = builder.build(route,
                Collections.singletonList(crossing("road-1", "road", road, null)), 50, true, true);

        assertEquals(3, segments.size());
        IntersectionSegment pass = segments.get(1);
        assertEquals(5, pass.getStartMeasureM(), 1e-8);
        assertEquals(15, pass.getEndMeasureM(), 1e-8);
        assertEquals(0, new BigDecimal("1.60").compareTo(pass.getMultiplier()));
        assertEquals(IntersectionSegment.Method.SPECIAL, pass.getMethod());
        assertTrue(pass.isStartTechnicalNode());
        assertTrue(pass.isEndTechnicalNode());
    }

    @Test
    void overlappingSpecialPassagesTakeMaximumMultiplierAndSplitOnActiveSetChange() throws Exception {
        LineString route = line("LINESTRING (0 0, 20 0)");
        Geometry roadway = polygon("POLYGON ((8 -5, 12 -5, 12 5, 8 5, 8 -5))");
        Geometry tramway = polygon("POLYGON ((9 -5, 13 -5, 13 5, 9 5, 9 -5))");

        List<IntersectionSegment> segments = builder.build(route, Arrays.asList(
                crossing("road-1", "road", roadway, null), crossing("tram-1", "tram_tracks", tramway, null)), 50, true, true);

        assertEquals(5, segments.size());
        IntersectionSegment overlap = segments.get(2);
        assertEquals(2, overlap.getRestrictionIds().size());
        assertEquals(0, new BigDecimal("1.75").compareTo(overlap.getMultiplier()));
        assertEquals(IntersectionSegment.Method.SPECIAL, overlap.getMethod());
    }

    @Test
    void separateCrossingsOfTheSameMultipartRestrictionRemainSeparate() throws Exception {
        LineString route = line("LINESTRING (0 0, 30 0)");
        Polygon first = polygon("POLYGON ((5 -5, 8 -5, 8 5, 5 5, 5 -5))");
        Polygon second = polygon("POLYGON ((20 -5, 23 -5, 23 5, 20 5, 20 -5))");
        GeometryFactory factory = new GeometryFactory();
        MultiPolygon parks = factory.createMultiPolygon(new Polygon[] {first, second});
        parks.setSRID(32637);

        List<IntersectionSegment> segments = builder.build(route,
                Collections.singletonList(crossing("road-pair", "road", parks, null)), 50, true, true);

        assertTrue(parks.intersects(route));
        long specialParts = segments.stream().filter(s -> s.getMethod() == IntersectionSegment.Method.SPECIAL).count();
        assertEquals(2, specialParts);
        for (IntersectionSegment passage : segments) {
            if (passage.getMethod() == IntersectionSegment.Method.SPECIAL) {
                assertTrue(passage.isStartTechnicalNode());
                assertTrue(passage.isEndTechnicalNode());
            }
        }
    }

    @Test
    void pipelinePassageExtendsTwoMetersEachSideAndSeparateIntersectionsStaySeparate() throws Exception {
        LineString route = line("LINESTRING (0 0, 20 0)");
        Geometry pipeline = projected("MULTILINESTRING ((5 -5, 5 5), (15 -5, 15 5))");

        List<IntersectionSegment> segments = builder.build(route,
                Collections.singletonList(crossing("gas", "gas_pipeline", pipeline, null)), 50, true, true);

        List<IntersectionSegment> passes = segments.stream()
                .filter(s -> s.getMethod() == IntersectionSegment.Method.SPECIAL).collect(java.util.stream.Collectors.toList());
        assertEquals(2, passes.size());
        assertEquals(2.6, passes.get(0).getStartMeasureM(), 1e-6);
        assertEquals(7.4, passes.get(0).getEndMeasureM(), 1e-6);
        assertEquals(12.6, passes.get(1).getStartMeasureM(), 1e-6);
        assertEquals(17.4, passes.get(1).getEndMeasureM(), 1e-6);
    }

    @Test
    void rejectsShallowRoadAngleAndBentPassage() throws Exception {
        LineString shallow = line("LINESTRING (0 -17.320508, 20 17.320508)");
        Geometry road = polygon("POLYGON ((8 -5, 12 -5, 12 5, 8 5, 8 -5))");
        assertThrows(InvalidSpecialCrossingException.class, () -> builder.build(shallow,
                Collections.singletonList(crossing("road", "road", road, null)), 50, true, true));

        LineString bent = line("LINESTRING (0 0, 10 0, 10 20)");
        Geometry wideRoad = polygon("POLYGON ((8 -5, 15 -5, 15 15, 8 15, 8 -5))");
        assertThrows(InvalidSpecialCrossingException.class, () -> builder.build(bent,
                Collections.singletonList(crossing("road", "road", wideRoad, null)), 50, true, true));
    }

    @Test
    void linearRoadRequiresDeclaredWidthAndWidthCreatesPolygonalPassage() throws Exception {
        LineString route = line("LINESTRING (0 0, 20 0)");
        Geometry roadAxis = line("LINESTRING (10 -5, 10 5)");
        assertThrows(InvalidSpecialCrossingException.class, () -> builder.build(route,
                Collections.singletonList(crossing("road", "road", roadAxis, null)), 50, true, true));
        List<IntersectionSegment> segments = builder.build(route,
                Collections.singletonList(crossing("road", "road", roadAxis, 4.0)), 50, true, true);
        IntersectionSegment pass = segments.stream().filter(s -> s.getMethod() == IntersectionSegment.Method.SPECIAL)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(5, pass.getStartMeasureM(), 1e-8);
        assertEquals(15, pass.getEndMeasureM(), 1e-8);
    }

    @Test
    void clearanceMustBeRestoredEvenAtADeclaredRouteEndpoint() throws Exception {
        LineString route = line("LINESTRING (0 0, 10 0)");
        Geometry gas = line("LINESTRING (9.5 -5, 9.5 5)");
        assertThrows(InvalidSpecialCrossingException.class, () -> builder.build(route,
                Collections.singletonList(crossing("gas", "gas_pipeline", gas, null)), 50, true, true));
    }

    @Test
    void activeRestrictionIdsRemainTyped() throws Exception {
        LineString route = line("LINESTRING (0 0, 20 0)");
        Geometry road = polygon("POLYGON ((8 -5, 12 -5, 12 5, 8 5, 8 -5))");
        List<IntersectionSegment> numeric = builder.build(route, Collections.singletonList(
                crossing(TypedId.of(new BigDecimal("1")), "road", road, null)), 50, true, true);
        List<IntersectionSegment> string = builder.build(route, Collections.singletonList(
                crossing(TypedId.of("1"), "road", road, null)), 50, true, true);
        assertFalse(numeric.get(1).getRestrictionIds().equals(string.get(1).getRestrictionIds()));
    }

    private static SpecialCrossingBuilder.CrossingRestriction crossing(String id, String type,
            Geometry geometry, Double widthM) {
        return crossing(TypedId.of(id), type, geometry, widthM);
    }
    private static SpecialCrossingBuilder.CrossingRestriction crossing(TypedId id, String type,
            Geometry geometry, Double widthM) {
        return new SpecialCrossingBuilder.CrossingRestriction(id, type, geometry, widthM);
    }
    private static LineString line(String wkt) throws Exception { return (LineString) projected(wkt); }
    private static Polygon polygon(String wkt) throws Exception { return (Polygon) projected(wkt); }
    private static Geometry projected(String wkt) throws Exception {
        Geometry geometry = new WKTReader().read(wkt);
        geometry.setSRID(32637);
        return geometry;
    }
}
