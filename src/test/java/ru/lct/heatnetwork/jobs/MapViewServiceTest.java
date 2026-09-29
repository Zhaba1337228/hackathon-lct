package ru.lct.heatnetwork.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.springframework.http.HttpStatus;
import ru.lct.heatnetwork.api.MapViewException;
import ru.lct.heatnetwork.api.MapViewResponse;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.spatial.MapFeaturePage;
import ru.lct.heatnetwork.spatial.MapViewStore;

class MapViewServiceTest {
    private final UUID jobId = UUID.randomUUID();
    private final CrsTransform transform = new CrsTransform();

    @Test
    void viewportUsesProjectedWgs84PolygonAndReturnsIndependentInputCursor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MapViewStore store = new EmptyMapViewStore() {
            @Override
            public MapFeaturePage findInputPage(UUID id, Geometry viewport, String cursor, int limit) {
                assertEquals(jobId, id);
                assertEquals("first-page", cursor);
                assertEquals(25, limit);
                assertEquals(32637, viewport.getSRID());
                assertTrue(viewport.contains(transform.toProjected(
                        new org.locationtech.jts.geom.GeometryFactory().createPoint(
                                new org.locationtech.jts.geom.Coordinate(37.6, 55.7)))));
                return new MapFeaturePage(Collections.singletonList(mapper.valueToTree(
                        Map.of("type", "Feature", "properties", Map.of("view_layer", "input"), "geometry", Map.of("type", "Point", "coordinates", new double[] {37.6, 55.7})))),
                        "next-input-page");
            }
        };
        MapViewService service = new MapViewService(store, ignored -> readyJob(), null, transform);

        MapViewResponse response = service.view(jobId, new double[] {37.5, 55.6, 37.7, 55.8},
                null, 25, "first-page", null);

        assertEquals("FeatureCollection", response.getType());
        assertEquals("READY", response.getJob().getStatus().name());
        assertEquals(1, response.getFeatures().size());
        assertEquals("next-input-page", response.getNextInputCursor());
        assertEquals(null, response.getNextVariantCursor());
    }

    @Test
    void rejectsInvalidWgs84BoundsAndOversizedPagesBeforeQueryingStore() {
        MapViewService service = new MapViewService(new EmptyMapViewStore(), ignored -> readyJob(), null, transform);

        MapViewException badBounds = assertThrows(MapViewException.class,
                () -> service.view(jobId, new double[] {37.7, 55.6, 37.5, 55.8}, null, 10, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, badBounds.getStatus());

        MapViewException badLimit = assertThrows(MapViewException.class,
                () -> service.view(jobId, new double[] {37.5, 55.6, 37.7, 55.8}, null, 501, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, badLimit.getStatus());
    }

    @Test
    void extentConvertsIndexedUtmBoundsBackToWgs84() {
        org.locationtech.jts.geom.Point source = new org.locationtech.jts.geom.GeometryFactory()
                .createPoint(new org.locationtech.jts.geom.Coordinate(37.6, 55.7));
        Geometry projected = transform.toProjected(source);
        projected.setSRID(32637);
        Envelope envelope = new Envelope(projected.getCoordinate().x - 100, projected.getCoordinate().x + 100,
                projected.getCoordinate().y - 100, projected.getCoordinate().y + 100);
        MapViewService service = new MapViewService(new EmptyMapViewStore() {
            @Override public Optional<Envelope> findProjectedInputExtent(UUID id) { return Optional.of(envelope); }
        }, ignored -> readyJob(), null, transform);

        double[] bbox = service.extent(jobId).getBbox();

        assertTrue(bbox[0] < 37.6 && bbox[2] > 37.6);
        assertTrue(bbox[1] < 55.7 && bbox[3] > 55.7);
    }

    @Test
    void extentReportsExpiredWhenCleanupFinishesDuringSpatialRead() {
        AtomicInteger reads = new AtomicInteger();
        MapViewService service = new MapViewService(new EmptyMapViewStore() {
            @Override public Optional<Envelope> findProjectedInputExtent(UUID id) {
                return Optional.of(new Envelope(400000, 400100, 6170000, 6170100));
            }
        }, ignored -> reads.incrementAndGet() == 1 ? readyJob() : expiredJob(), null, transform);

        JobCalculationException failure = assertThrows(JobCalculationException.class, () -> service.extent(jobId));

        assertEquals(JobCalculationException.Kind.RESULT_EXPIRED, failure.getKind());
        assertEquals(2, reads.get());
    }

    @Test
    void viewportReportsExpiredAsSoonAsCleanupClaimsTheJob() {
        AtomicInteger reads = new AtomicInteger();
        MapViewStore store = new EmptyMapViewStore() {
            @Override public MapFeaturePage findInputPage(UUID id, Geometry viewport, String cursor, int limit) {
                return new MapFeaturePage(Collections.emptyList(), null);
            }
        };
        MapViewService service = new MapViewService(store,
                ignored -> reads.incrementAndGet() == 1 ? readyJob() : cleanupClaimedJob(), null, transform);

        JobCalculationException failure = assertThrows(JobCalculationException.class,
                () -> service.view(jobId, new double[] {37.5, 55.6, 37.7, 55.8}, null, 20, null, null));

        assertEquals(JobCalculationException.Kind.RESULT_EXPIRED, failure.getKind());
        assertEquals(2, reads.get());
    }

    @Test
    void viewportReportsExpiredWhenCleanupFinishesDuringSpatialRead() {
        AtomicInteger reads = new AtomicInteger();
        MapViewStore store = new EmptyMapViewStore() {
            @Override public MapFeaturePage findInputPage(UUID id, Geometry viewport, String cursor, int limit) {
                return new MapFeaturePage(Collections.emptyList(), null);
            }
        };
        MapViewService service = new MapViewService(store,
                ignored -> reads.incrementAndGet() == 1 ? readyJob() : expiredJob(), null, transform);

        JobCalculationException failure = assertThrows(JobCalculationException.class,
                () -> service.view(jobId, new double[] {37.5, 55.6, 37.7, 55.8}, null, 20, null, null));

        assertEquals(JobCalculationException.Kind.RESULT_EXPIRED, failure.getKind());
        assertEquals(2, reads.get());
    }

    @Test
    void malformedCursorFromStoreBecomesClientError() {
        MapViewStore store = new EmptyMapViewStore() {
            @Override public MapFeaturePage findInputPage(UUID id, Geometry viewport, String cursor, int limit) {
                throw new IllegalArgumentException("Invalid map page cursor");
            }
        };
        MapViewService service = new MapViewService(store, ignored -> readyJob(), null, transform);

        MapViewException failure = assertThrows(MapViewException.class,
                () -> service.view(jobId, new double[] {37.5, 55.6, 37.7, 55.8}, null, 20, "bad", null));

        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
    }

    private Job readyJob() {
        return new Job(jobId, Job.Status.READY, null, Instant.EPOCH, Instant.EPOCH, 10, 1,
                Map.of("source", 1L), null, null, null, null);
    }

    private Job expiredJob() {
        return new Job(jobId, Job.Status.READY, null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 10, 1,
                Map.of("source", 1L), null, null, null, null);
    }

    private Job cleanupClaimedJob() {
        return new Job(jobId, Job.Status.SUCCEEDED, null, Instant.EPOCH, Instant.EPOCH, null, Instant.EPOCH,
                10, 1, Map.of("source", 1L), null, null, null, null);
    }

    private static class EmptyMapViewStore implements MapViewStore {
        @Override public Optional<Envelope> findProjectedInputExtent(UUID id) { return Optional.empty(); }
        @Override public MapFeaturePage findInputPage(UUID id, Geometry viewport, String cursor, int limit) {
            return new MapFeaturePage(Collections.emptyList(), null);
        }
        @Override public MapFeaturePage findVariantPage(UUID id, String variantId, Geometry viewport,
                String cursor, int limit) { return new MapFeaturePage(Collections.emptyList(), null); }
        @Override public void replaceVariantFeatures(UUID id, java.nio.file.Path file) { }
        @Override public void deleteVariantFeatures(UUID id) { }
    }
}
