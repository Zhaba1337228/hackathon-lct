package ru.lct.heatnetwork.projection;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.GeometryEditor;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Component;

/** Transforms GeoJSON CRS84 coordinates (longitude, latitude) to metric UTM zone 37N. */
@Component
public final class CrsTransform {
    public static final int PROJECTED_SRID = 32637;
    private final org.locationtech.proj4j.CoordinateTransform forward;
    private final org.locationtech.proj4j.CoordinateTransform inverse;

    public CrsTransform() {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem geographic = factory.createFromName("EPSG:4326");
        CoordinateReferenceSystem projected = factory.createFromName("EPSG:32637");
        CoordinateTransformFactory transforms = new CoordinateTransformFactory();
        this.forward = transforms.createTransform(geographic, projected);
        this.inverse = transforms.createTransform(projected, geographic);
    }

    public Geometry toProjected(Geometry geometry) {
        return transform(geometry, forward, PROJECTED_SRID);
    }

    public Geometry toGeographic(Geometry geometry) {
        return transform(geometry, inverse, 4326);
    }

    private static Geometry transform(Geometry source, org.locationtech.proj4j.CoordinateTransform transform,
            int srid) {
        if (source == null) throw new IllegalArgumentException("geometry is required");
        GeometryFactory sourceFactory = source.getFactory();
        GeometryFactory targetFactory = new GeometryFactory(sourceFactory.getPrecisionModel(), srid,
                sourceFactory.getCoordinateSequenceFactory());
        Geometry result = new GeometryEditor(targetFactory).edit(source,
                new GeometryEditor.CoordinateOperation() {
                    @Override
                    public Coordinate[] edit(Coordinate[] coordinates, Geometry geometry) {
                        Coordinate[] transformed = new Coordinate[coordinates.length];
                        for (int index = 0; index < coordinates.length; index++) {
                            Coordinate coordinate = new Coordinate(coordinates[index]);
                            ProjCoordinate projected = transform.transform(
                                    new ProjCoordinate(coordinate.x, coordinate.y), new ProjCoordinate());
                            coordinate.x = projected.x;
                            coordinate.y = projected.y;
                            transformed[index] = coordinate;
                        }
                        return transformed;
                    }
                });
        result.setUserData(source.getUserData());
        for (Coordinate coordinate : result.getCoordinates()) {
            if (!Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)) {
                throw new IllegalArgumentException("coordinate transform produced a non-finite value");
            }
        }
        return result;
    }
}
