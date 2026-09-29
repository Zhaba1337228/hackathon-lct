package ru.lct.heatnetwork.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

/** Caller-supplied WGS84 search extent, ordered west, south, east, north. */
public final class Wgs84BoundingBox {
    private static final int EDGE_SUBDIVISIONS = 256;

    private final double west;
    private final double south;
    private final double east;
    private final double north;

    private Wgs84BoundingBox(double west, double south, double east, double north) {
        if (!Double.isFinite(west) || !Double.isFinite(south)
                || !Double.isFinite(east) || !Double.isFinite(north)) {
            throw new IllegalArgumentException("bbox coordinates must be finite numbers");
        }
        if (west < -180 || west > 180 || east < -180 || east > 180
                || south < -90 || south > 90 || north < -90 || north > 90) {
            throw new IllegalArgumentException("bbox coordinates must be within WGS84 longitude and latitude ranges");
        }
        if (west >= east || south >= north) {
            throw new IllegalArgumentException("bbox requires west < east and south < north; antimeridian crossing is unsupported");
        }
        this.west = west;
        this.south = south;
        this.east = east;
        this.north = north;
    }

    public static Wgs84BoundingBox fromArray(double[] coordinates) {
        if (coordinates == null || coordinates.length != 4) {
            throw new IllegalArgumentException("bbox must contain [west,south,east,north]");
        }
        return new Wgs84BoundingBox(coordinates[0], coordinates[1], coordinates[2], coordinates[3]);
    }

    public double getWest() { return west; }
    public double getSouth() { return south; }
    public double getEast() { return east; }
    public double getNorth() { return north; }

    /** Projects a sampled WGS84 perimeter and returns the router's metric envelope. */
    public Envelope toProjectedEnvelope(CrsTransform crsTransform) {
        return new Envelope(toProjectedPolygon(crsTransform).getEnvelopeInternal());
    }

    /** Projects the sampled WGS84 viewport perimeter for exact spatial intersection checks. */
    public Polygon toProjectedPolygon(CrsTransform crsTransform) {
        Objects.requireNonNull(crsTransform, "crsTransform");
        GeometryFactory factory = new GeometryFactory();
        LinearRing perimeter = factory.createLinearRing(sampledPerimeter());
        Polygon geographic = factory.createPolygon(perimeter);
        geographic.setSRID(4326);
        Geometry projected = crsTransform.toProjected(geographic);
        Envelope bounds = projected.getEnvelopeInternal();
        if (bounds.isNull() || !Double.isFinite(bounds.getMinX()) || !Double.isFinite(bounds.getMinY())
                || !Double.isFinite(bounds.getMaxX()) || !Double.isFinite(bounds.getMaxY())
                || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            throw new IllegalArgumentException("bbox could not be projected to a non-empty EPSG:32637 search area");
        }
        return (Polygon) projected;
    }

    private Coordinate[] sampledPerimeter() {
        List<Coordinate> coordinates = new ArrayList<>(EDGE_SUBDIVISIONS * 4 + 1);
        addEdge(coordinates, west, south, east, south);
        addEdge(coordinates, east, south, east, north);
        addEdge(coordinates, east, north, west, north);
        addEdge(coordinates, west, north, west, south);
        coordinates.add(new Coordinate(west, south));
        return coordinates.toArray(new Coordinate[0]);
    }

    private static void addEdge(List<Coordinate> coordinates, double x1, double y1, double x2, double y2) {
        for (int index = 0; index < EDGE_SUBDIVISIONS; index++) {
            double ratio = (double) index / EDGE_SUBDIVISIONS;
            coordinates.add(new Coordinate(x1 + (x2 - x1) * ratio, y1 + (y2 - y1) * ratio));
        }
    }
}
