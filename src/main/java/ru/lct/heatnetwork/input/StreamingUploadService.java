package ru.lct.heatnetwork.input;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.lct.heatnetwork.spatial.FeatureIndex;

@Service
public final class StreamingUploadService {
    private final Path storageRoot;
    private final long maxBytes;
    private final GeoJsonStreamReader reader;
    private final FeatureIndex featureIndex;

    @Autowired
    public StreamingUploadService(
            @Value("${app.storage.root:./data/jobs}") String storageRoot,
            @Value("${app.upload.max-bytes:3000000000}") long maxBytes,
            GeoJsonStreamReader reader, FeatureIndex featureIndex) {
        this(Paths.get(storageRoot), maxBytes, reader, featureIndex);
    }

    public StreamingUploadService(Path storageRoot, long maxBytes, GeoJsonStreamReader reader) {
        this(storageRoot, maxBytes, reader, new FeatureIndex() {
            @Override public void store(UUID jobId, ru.lct.heatnetwork.domain.InputFeature feature) { }
            @Override public java.util.List<IndexedFeature> findCandidates(UUID jobId,
                    org.locationtech.jts.geom.Geometry corridor) { return java.util.Collections.emptyList(); }
            @Override public void deleteJob(UUID jobId) { }
        });
    }

    public StreamingUploadService(Path storageRoot, long maxBytes, GeoJsonStreamReader reader,
            FeatureIndex featureIndex) {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.reader = reader;
        this.featureIndex = featureIndex;
    }

    public UploadResult accept(InputStream requestBody) throws IOException {
        return accept(UUID.randomUUID(), requestBody);
    }

    public UploadResult accept(UUID jobId, InputStream requestBody) throws IOException {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        Path jobDirectory = storageRoot.resolve(jobId.toString()).normalize();
        if (!jobDirectory.startsWith(storageRoot)) throw new IllegalStateException("Invalid job directory");
        Files.createDirectories(jobDirectory);
        Path inputFile = jobDirectory.resolve("input.geojson");
        try {
            long receivedBytes = copyWithLimit(requestBody, inputFile);
            GeoJsonStreamReader.Summary summary;
            try (InputStream savedInput = Files.newInputStream(inputFile)) {
                summary = reader.read(savedInput, feature -> featureIndex.store(jobId, feature));
            }
            return new UploadResult(jobId, receivedBytes, summary);
        } catch (IOException | RuntimeException failure) {
            try {
                deleteJobDirectory(jobDirectory);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                featureIndex.deleteJob(jobId);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public void discard(UUID jobId) throws IOException {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        Path jobDirectory = storageRoot.resolve(jobId.toString()).normalize();
        if (!jobDirectory.startsWith(storageRoot)) throw new IllegalStateException("Invalid job directory");
        deleteJobDirectory(jobDirectory);
        featureIndex.deleteJob(jobId);
    }

    private long copyWithLimit(InputStream input, Path outputPath) throws IOException {
        long received = 0;
        byte[] buffer = new byte[16 * 1024];
        try (InputStream source = input; OutputStream output = Files.newOutputStream(outputPath)) {
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (received > maxBytes - count) throw new PayloadTooLargeException(maxBytes);
                received += count;
                output.write(buffer, 0, count);
            }
        }
        return received;
    }

    private static void deleteJobDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            Path[] entries = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path entry : entries) Files.deleteIfExists(entry);
        }
    }
}
