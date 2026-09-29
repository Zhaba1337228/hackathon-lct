package ru.lct.heatnetwork.routing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import org.locationtech.jts.geom.Geometry;

/** Reuses immutable expanded geometries within one bounded batch of route searches. */
final class ObstacleBufferCache {
    private final ConcurrentMap<Key, Geometry> buffers = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    Geometry buffer(Geometry source, double distanceM, int quadrantSegments) {
        if (source == null || source.isEmpty() || !Double.isFinite(distanceM) || distanceM <= 0
                || quadrantSegments < 1) {
            throw new IllegalArgumentException("A non-empty geometry and positive buffer parameters are required");
        }
        Key key = new Key(source, distanceM, quadrantSegments);
        Geometry existing = buffers.get(key);
        if (existing != null) {
            hits.increment();
            return existing;
        }
        return buffers.computeIfAbsent(key,
                ignored -> {
                    misses.increment();
                    return source.buffer(distanceM, quadrantSegments);
                });
    }

    long hits() { return hits.sum(); }
    long misses() { return misses.sum(); }
    int size() { return buffers.size(); }

    void clear() {
        buffers.clear();
    }

    private static final class Key {
        private final Geometry source;
        private final long distanceBits;
        private final int quadrantSegments;
        private final int hashCode;

        private Key(Geometry source, double distanceM, int quadrantSegments) {
            this.source = source;
            this.distanceBits = Double.doubleToLongBits(distanceM);
            this.quadrantSegments = quadrantSegments;
            this.hashCode = 31 * (31 * System.identityHashCode(source) + Long.hashCode(distanceBits))
                    + quadrantSegments;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return source == key.source && distanceBits == key.distanceBits
                    && quadrantSegments == key.quadrantSegments;
        }
    }
}
