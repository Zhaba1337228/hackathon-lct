package ru.lct.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;

class ObstacleBufferCacheTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);

    @Test
    void reusesOnlyBuffersWithTheSameGeometryDistanceAndQuadrantSegments() throws Exception {
        Geometry source = new WKTReader(FACTORY).read("POLYGON ((0 0, 4 0, 4 4, 0 4, 0 0))");
        ObstacleBufferCache cache = new ObstacleBufferCache();

        Geometry first = cache.buffer(source, 1.2, 8);

        assertSame(first, cache.buffer(source, 1.2, 8));
        assertNotSame(first, cache.buffer(source, 1.3, 8));
        assertNotSame(first, cache.buffer(source, 1.2, 4));
        assertTrue(first.covers(source));
    }

    @Test
    void concurrentSearchesReuseTheSameImmutableBuffer() throws Exception {
        Geometry source = new WKTReader(FACTORY).read("POLYGON ((0 0, 4 0, 4 4, 0 4, 0 0))");
        ObstacleBufferCache cache = new ObstacleBufferCache();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Geometry>> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) requests.add(() -> cache.buffer(source, 1.2, 8));
            List<Future<Geometry>> buffers = executor.invokeAll(requests);
            Geometry first = buffers.get(0).get();
            for (Future<Geometry> buffer : buffers) assertSame(first, buffer.get());
            assertTrue(first.covers(source));
        } finally {
            executor.shutdownNow();
        }
    }
}
