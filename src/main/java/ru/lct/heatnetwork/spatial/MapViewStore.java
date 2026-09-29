package ru.lct.heatnetwork.spatial;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

/** Job-scoped spatial reads and the indexed materialization of completed result geometries. */
public interface MapViewStore {
    Optional<Envelope> findProjectedInputExtent(UUID jobId);

    MapFeaturePage findInputPage(UUID jobId, Geometry projectedViewport, String cursor, int limit);

    MapFeaturePage findVariantPage(UUID jobId, String variantId, Geometry projectedViewport,
            String cursor, int limit);

    void replaceVariantFeatures(UUID jobId, Path geoJsonFile) throws IOException;

    void deleteVariantFeatures(UUID jobId);
}
