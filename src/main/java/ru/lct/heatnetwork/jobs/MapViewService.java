package ru.lct.heatnetwork.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lct.heatnetwork.api.CalculationResultResponse;
import ru.lct.heatnetwork.api.JobStatusResponse;
import ru.lct.heatnetwork.api.MapExtentResponse;
import ru.lct.heatnetwork.api.MapViewException;
import ru.lct.heatnetwork.api.MapViewResponse;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.projection.Wgs84BoundingBox;
import ru.lct.heatnetwork.spatial.MapFeaturePage;
import ru.lct.heatnetwork.spatial.MapViewStore;

/** Coordinates job state, WGS84 view bounds and bounded indexed map queries. */
@Service
public final class MapViewService {
    public static final int DEFAULT_PAGE_SIZE = 500;
    public static final int MAXIMUM_PAGE_SIZE = 500;
    private static final int EXTENT_EDGE_SUBDIVISIONS = 64;

    private final MapViewStore store;
    private final JobLookup jobs;
    private final JobCalculationService calculations;
    private final CrsTransform crsTransform;

    public MapViewService(MapViewStore store, JobLookup jobs, JobCalculationService calculations,
            CrsTransform crsTransform) {
        this.store = store;
        this.jobs = jobs;
        this.calculations = calculations;
        this.crsTransform = crsTransform;
    }

    public MapExtentResponse extent(UUID jobId) {
        Job job = getAvailableJob(jobId);
        Optional<Envelope> projected = store.findProjectedInputExtent(jobId);
        job = getAvailableJob(jobId);
        if (!projected.isPresent() || projected.get().isNull()) {
            throw new MapViewException("EMPTY_DATASET", HttpStatus.CONFLICT,
                    "The uploaded dataset has no map features");
        }
        Envelope geographic = toGeographicEnvelope(projected.get());
        if (!Double.isFinite(geographic.getMinX()) || !Double.isFinite(geographic.getMinY())
                || !Double.isFinite(geographic.getMaxX()) || !Double.isFinite(geographic.getMaxY())) {
            throw new MapViewException("INVALID_DATASET_EXTENT", HttpStatus.CONFLICT,
                    "The uploaded dataset extent cannot be displayed in WGS84");
        }
        return new MapExtentResponse(job.getId(), geographic.getMinX(), geographic.getMinY(),
                geographic.getMaxX(), geographic.getMaxY());
    }

    public MapViewResponse view(UUID jobId, double[] bbox, String variantId, int limit,
            String inputCursor, String variantCursor) {
        Job job = getAvailableJob(jobId);
        if (limit < 1 || limit > MAXIMUM_PAGE_SIZE) {
            throw new MapViewException("INVALID_VIEW_REQUEST", HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAXIMUM_PAGE_SIZE);
        }
        final Polygon projectedViewport;
        try {
            projectedViewport = Wgs84BoundingBox.fromArray(bbox).toProjectedPolygon(crsTransform);
        } catch (IllegalArgumentException invalid) {
            throw new MapViewException("INVALID_VIEW_REQUEST", HttpStatus.BAD_REQUEST, invalid.getMessage());
        }

        if (variantId != null && !variantId.trim().isEmpty()) validateVariant(jobId, variantId);
        try {
            MapFeaturePage inputs = store.findInputPage(jobId, projectedViewport, inputCursor, limit);
            MapFeaturePage variant = variantId == null || variantId.trim().isEmpty()
                    ? new MapFeaturePage(java.util.Collections.emptyList(), null)
                    : store.findVariantPage(jobId, variantId, projectedViewport, variantCursor, limit);
            job = getAvailableJob(jobId);
            List<JsonNode> features = new ArrayList<>(inputs.getFeatures().size() + variant.getFeatures().size());
            features.addAll(inputs.getFeatures());
            features.addAll(variant.getFeatures());
            return new MapViewResponse(jobId, variantId, JobStatusResponse.from(job), features,
                    inputs.getNextCursor(), variant.getNextCursor());
        } catch (IllegalArgumentException invalidCursor) {
            throw new MapViewException("INVALID_VIEW_REQUEST", HttpStatus.BAD_REQUEST, invalidCursor.getMessage());
        }
    }

    private Job getAvailableJob(UUID jobId) {
        Job job = jobs.get(jobId);
        if (job.getArtifactsPurgedAt() != null || job.getArtifactCleanupAttemptedAt() != null) {
            throw new JobCalculationException(JobCalculationException.Kind.RESULT_EXPIRED,
                    "The map data for this job has expired");
        }
        return job;
    }

    private void validateVariant(UUID jobId, String variantId) {
        CalculationResultResponse result = calculations.result(jobId);
        for (CalculationResultResponse.VariantSummary variant : result.getVariants()) {
            if (variantId.equals(variant.getVariantId())) return;
        }
        throw new MapViewException("VARIANT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "The requested calculation variant does not exist");
    }

    private Envelope toGeographicEnvelope(Envelope projectedEnvelope) {
        GeometryFactory factory = new GeometryFactory();
        Coordinate[] boundary = sampleBoundary(projectedEnvelope);
        LinearRing ring = factory.createLinearRing(boundary);
        Polygon projected = factory.createPolygon(ring);
        projected.setSRID(CrsTransform.PROJECTED_SRID);
        Geometry geographic = crsTransform.toGeographic(projected);
        return geographic.getEnvelopeInternal();
    }

    private static Coordinate[] sampleBoundary(Envelope envelope) {
        List<Coordinate> points = new ArrayList<>(EXTENT_EDGE_SUBDIVISIONS * 4 + 1);
        addEdge(points, envelope.getMinX(), envelope.getMinY(), envelope.getMaxX(), envelope.getMinY());
        addEdge(points, envelope.getMaxX(), envelope.getMinY(), envelope.getMaxX(), envelope.getMaxY());
        addEdge(points, envelope.getMaxX(), envelope.getMaxY(), envelope.getMinX(), envelope.getMaxY());
        addEdge(points, envelope.getMinX(), envelope.getMaxY(), envelope.getMinX(), envelope.getMinY());
        points.add(new Coordinate(envelope.getMinX(), envelope.getMinY()));
        return points.toArray(new Coordinate[0]);
    }

    private static void addEdge(List<Coordinate> points, double x1, double y1, double x2, double y2) {
        for (int index = 0; index < EXTENT_EDGE_SUBDIVISIONS; index++) {
            double ratio = (double) index / EXTENT_EDGE_SUBDIVISIONS;
            points.add(new Coordinate(x1 + (x2 - x1) * ratio, y1 + (y2 - y1) * ratio));
        }
    }
}
