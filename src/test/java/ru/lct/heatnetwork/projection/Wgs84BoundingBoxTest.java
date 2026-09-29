package ru.lct.heatnetwork.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

class Wgs84BoundingBoxTest {
    private final CrsTransform transform = new CrsTransform();
    private final GeometryFactory geometries = new GeometryFactory();

    @Test
    void projectsMoscowBboxToFiniteMetricEnvelopeContainingItsCorners() {
        Wgs84BoundingBox bounds = Wgs84BoundingBox.fromArray(new double[] {37.5, 55.6, 37.7, 55.8});

        Envelope projected = bounds.toProjectedEnvelope(transform);

        assertTrue(projected.getWidth() > 0);
        assertTrue(projected.getHeight() > 0);
        for (Coordinate corner : new Coordinate[] {
                new Coordinate(37.5, 55.6), new Coordinate(37.7, 55.6),
                new Coordinate(37.7, 55.8), new Coordinate(37.5, 55.8)}) {
            Point point = geometries.createPoint(corner);
            point.setSRID(4326);
            Point projectedPoint = (Point) transform.toProjected(point);
            assertTrue(projected.contains(projectedPoint.getX(), projectedPoint.getY()));
        }
        assertEquals(CrsTransform.PROJECTED_SRID,
                transform.toProjected(geometries.createPoint(new Coordinate(37.5, 55.6))).getSRID());
    }

    @Test
    void rejectsMalformedOrOutOfRangeBboxCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> Wgs84BoundingBox.fromArray(null));
        assertThrows(IllegalArgumentException.class, () -> Wgs84BoundingBox.fromArray(new double[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> Wgs84BoundingBox.fromArray(new double[] {Double.NaN, 1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> Wgs84BoundingBox.fromArray(new double[] {-181, 1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> Wgs84BoundingBox.fromArray(new double[] {1, -91, 2, 3}));
    }

    @Test
    void rejectsReversedDegenerateAndAntimeridianCrossingBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> Wgs84BoundingBox.fromArray(new double[] {5, 1, 4, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> Wgs84BoundingBox.fromArray(new double[] {1, 3, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> Wgs84BoundingBox.fromArray(new double[] {170, -10, -170, 10}));
    }
}
