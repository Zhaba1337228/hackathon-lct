package ru.lct.heatnetwork.spatial;

import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatnetwork.domain.InputFeature;

/** Job-scoped projected feature storage and conservative corridor candidate queries. */
public interface FeatureIndex {
    void store(UUID jobId, InputFeature feature);
    List<IndexedFeature> findCandidates(UUID jobId, Geometry corridor);
    /** Returns all connection targets plus objects close enough to affect clearances inside the search area. */
    default List<IndexedFeature> findCalculationContext(UUID jobId, Geometry searchArea, double marginM) {
        if (searchArea == null || searchArea.isEmpty() || !Double.isFinite(marginM) || marginM < 0) {
            throw new IllegalArgumentException("A projected search area and non-negative context margin are required");
        }
        Geometry expanded = searchArea.buffer(marginM);
        List<IndexedFeature> result = new java.util.ArrayList<>(findCandidates(jobId, expanded));
        return result;
    }
    void deleteJob(UUID jobId);

    final class IndexedFeature {
        private final InputFeature feature;
        private final Geometry geometry;

        public IndexedFeature(InputFeature feature, Geometry geometry) {
            this.feature = feature;
            this.geometry = geometry;
        }

        public InputFeature getFeature() { return feature; }
        public Geometry getGeometry() { return geometry; }
    }
}
