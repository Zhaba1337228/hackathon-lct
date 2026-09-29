package ru.lct.heatnetwork.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonReader;

class CrsTransformTest {
    private final CrsTransform transform = new CrsTransform();

    @Test
    void projectsMoscowPointToMetricUtmAndRoundTrips() throws Exception {
        Geometry input = new GeoJsonReader().read(
                "{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}");

        Geometry projected = transform.toProjected(input);
        Geometry restored = transform.toGeographic(projected);

        assertEquals(32637, projected.getSRID());
        assertEquals(32637, projected.getFactory().getSRID());
        assertEquals(4326, restored.getFactory().getSRID());
        assertTrue(projected.getCoordinate().x > 400_000 && projected.getCoordinate().x < 430_000);
        assertTrue(projected.getCoordinate().y > 6_100_000 && projected.getCoordinate().y < 6_300_000);
        assertEquals(0, input.getCoordinate().distance(restored.getCoordinate()), 1e-7);
    }

    @Test
    void roundTripsMultiLineAndPolygonCoordinates() throws Exception {
        Geometry[] examples = {
            new GeoJsonReader().read("{\"type\":\"MultiLineString\",\"coordinates\":[[[37.6,55.7],[37.61,55.71]],[[37.62,55.72],[37.63,55.73]]]}"),
            new GeoJsonReader().read("{\"type\":\"Polygon\",\"coordinates\":[[[37.6,55.7],[37.61,55.7],[37.61,55.71],[37.6,55.7]]]}"),
            new GeoJsonReader().read("{\"type\":\"MultiPolygon\",\"coordinates\":[[[[37.6,55.7],[37.61,55.7],[37.61,55.71],[37.6,55.7]]]]}")
        };

        for (Geometry input : examples) {
            Geometry restored = transform.toGeographic(transform.toProjected(input));
            assertEquals(input.getGeometryType(), restored.getGeometryType());
            assertTrue(input.equalsExact(restored, 1e-7));
        }
    }
}
