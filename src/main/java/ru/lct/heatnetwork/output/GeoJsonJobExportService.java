package ru.lct.heatnetwork.output;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.jobs.CalculationExportPublisher;
import ru.lct.heatnetwork.jobs.JobCalculationException;
import ru.lct.heatnetwork.jobs.JobNotFoundException;
import ru.lct.heatnetwork.jobs.JobLookup;
import ru.lct.heatnetwork.costing.CostedVariant;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.spatial.MapViewStore;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Stores one completed FeatureCollection beside the uploaded file for the job. */
@Service
public final class GeoJsonJobExportService implements CalculationExportPublisher {
    public static final long DEFAULT_MAXIMUM_BYTES = 500_000_000L;
    public static final String FILE_NAME = "export.geojson";

    private final Path storageRoot;
    private final GeoJsonStreamWriter writer;
    private final long maximumBytes;
    private final JobLookup jobService;
    private final MapViewStore mapViewStore;

    @Autowired
    public GeoJsonJobExportService(
            @Value("${app.storage.root:./data/jobs}") String storageRoot,
            @Value("${app.export.max-bytes:500000000}") long maximumBytes,
            ObjectMapper mapper, CrsTransform crsTransform, JobLookup jobService, MapViewStore mapViewStore) {
        this(Paths.get(storageRoot), maximumBytes, mapper, crsTransform, jobService, mapViewStore);
    }

    public GeoJsonJobExportService(Path storageRoot, long maximumBytes,
            ObjectMapper mapper, CrsTransform crsTransform, JobLookup jobService) {
        this(storageRoot, maximumBytes, mapper, crsTransform, jobService, null);
    }

    public GeoJsonJobExportService(Path storageRoot, long maximumBytes,
            ObjectMapper mapper, CrsTransform crsTransform, JobLookup jobService, MapViewStore mapViewStore) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes must be positive");
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.maximumBytes = maximumBytes;
        this.writer = new GeoJsonStreamWriter(mapper, crsTransform, maximumBytes);
        this.jobService = jobService;
        this.mapViewStore = mapViewStore;
    }

    @Override
    public void publish(UUID jobId, List<CostedVariant> variants,
            List<FeatureIndex.IndexedFeature> inputs) throws IOException {
        Path directory = jobDirectory(jobId);
        Files.createDirectories(directory);
        Path partial = directory.resolve(FILE_NAME + "." + UUID.randomUUID() + ".partial");
        Path complete = directory.resolve(FILE_NAME);
        boolean published = false;
        boolean indexed = false;
        try {
            try (OutputStream output = Files.newOutputStream(partial)) {
                writer.write(output, jobId, variants, inputs);
            }
            long actualBytes = Files.size(partial);
            if (actualBytes > maximumBytes) throw new GeoJsonExportTooLargeException(maximumBytes);
            if (mapViewStore != null) {
                mapViewStore.replaceVariantFeatures(jobId, partial);
                indexed = true;
            }
            try {
                Files.move(partial, complete, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic export publication is not supported by the job storage", unsupported);
            }
            published = true;
        } catch (IOException | RuntimeException failure) {
            if (indexed) {
                try {
                    mapViewStore.deleteVariantFeatures(jobId);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        } finally {
            if (!published) Files.deleteIfExists(partial);
        }
    }

    public Download openDownload(UUID jobId) {
        Job job = jobService.get(jobId);
        if (job.getArtifactsPurgedAt() != null || job.getArtifactCleanupAttemptedAt() != null) {
            throw new JobCalculationException(JobCalculationException.Kind.RESULT_EXPIRED,
                    "The calculation result has expired");
        }
        if (job.getStatus() != Job.Status.SUCCEEDED) {
            throw new JobCalculationException(JobCalculationException.Kind.RESULT_UNAVAILABLE,
                    "The export is not available until calculation succeeds");
        }

        Path complete = jobDirectory(jobId).resolve(FILE_NAME);
        try {
            long size = Files.size(complete);
            if (size > maximumBytes) throw new GeoJsonExportTooLargeException(maximumBytes);
            return new Download(Files.newInputStream(complete), size);
        } catch (GeoJsonExportTooLargeException tooLarge) {
            throw new JobCalculationException(JobCalculationException.Kind.RESULT_UNAVAILABLE,
                    "The completed export exceeds the configured size limit");
        } catch (IOException unavailable) {
            Job latest = jobService.get(jobId);
            if (latest.getArtifactsPurgedAt() != null || latest.getArtifactCleanupAttemptedAt() != null) {
                throw new JobCalculationException(JobCalculationException.Kind.RESULT_EXPIRED,
                        "The calculation result has expired");
            }
            throw new JobCalculationException(JobCalculationException.Kind.RESULT_UNAVAILABLE,
                    "The completed export is unavailable");
        }
    }

    Path jobDirectory(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        Path directory = storageRoot.resolve(jobId.toString()).normalize();
        if (!directory.startsWith(storageRoot)) throw new IllegalStateException("Invalid job directory");
        return directory;
    }

    public static final class Download implements AutoCloseable {
        private final InputStream input;
        private final long size;
        public Download(InputStream input, long size) { this.input = input; this.size = size; }
        public InputStream getInput() { return input; }
        public long getSize() { return size; }
        @Override public void close() throws IOException { input.close(); }
    }
}
