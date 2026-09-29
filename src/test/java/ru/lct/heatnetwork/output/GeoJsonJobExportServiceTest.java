package ru.lct.heatnetwork.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.jobs.JobLookup;
import ru.lct.heatnetwork.projection.CrsTransform;

class GeoJsonJobExportServiceTest {
    @TempDir
    Path storage;

    @Test
    void publishesOnlyCompleteAtomicArtifactAndOpensItForASucceededJob() throws Exception {
        assertEquals(500_000_000L, GeoJsonJobExportService.DEFAULT_MAXIMUM_BYTES);
        UUID id = UUID.randomUUID();
        JobLookup jobs = ignored -> job(id, Job.Status.SUCCEEDED);
        GeoJsonJobExportService service = new GeoJsonJobExportService(storage, 1_000_000,
                new ObjectMapper(), new CrsTransform(), jobs);

        service.publish(id, Collections.emptyList(), Collections.emptyList());

        Path directory = storage.resolve(id.toString());
        Path complete = directory.resolve(GeoJsonJobExportService.FILE_NAME);
        assertTrue(Files.isRegularFile(complete));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(1, files.count(), "no partial file remains after publication");
        }
        try (GeoJsonJobExportService.Download download = service.openDownload(id)) {
            byte[] bytes = download.getInput().readAllBytes();
            assertEquals(bytes.length, download.getSize());
            assertEquals("FeatureCollection", new ObjectMapper().readTree(bytes).path("type").asText());
        }
    }

    @Test
    void removesPartialArtifactWhenWritingExceedsTheConfiguredLimit() throws Exception {
        UUID id = UUID.randomUUID();
        GeoJsonJobExportService service = new GeoJsonJobExportService(storage, 8,
                new ObjectMapper(), new CrsTransform(), ignored -> job(id, Job.Status.SUCCEEDED));

        org.junit.jupiter.api.Assertions.assertThrows(GeoJsonExportTooLargeException.class,
                () -> service.publish(id, Collections.emptyList(), Collections.emptyList()));

        Path directory = storage.resolve(id.toString());
        assertFalse(Files.exists(directory.resolve(GeoJsonJobExportService.FILE_NAME)));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count(), "failed writes leave no publishable or partial artifact");
        }
    }

    @Test
    void exportIsUnavailableBeforeSuccessAndExpiredAfterRetentionCleanup() {
        UUID id = UUID.randomUUID();
        AtomicReference<Job> state = new AtomicReference<>(job(id, Job.Status.CALCULATING));
        JobLookup jobs = ignored -> state.get();
        GeoJsonJobExportService service = new GeoJsonJobExportService(storage, 100_000,
                new ObjectMapper(), new CrsTransform(), jobs);

        org.junit.jupiter.api.Assertions.assertEquals(
                ru.lct.heatnetwork.jobs.JobCalculationException.Kind.RESULT_UNAVAILABLE,
                org.junit.jupiter.api.Assertions.assertThrows(ru.lct.heatnetwork.jobs.JobCalculationException.class,
                        () -> service.openDownload(id)).getKind());

        state.set(new Job(id, Job.Status.SUCCEEDED, null, Instant.EPOCH, Instant.EPOCH,
                Instant.now(), 0, 0, Map.of(), null, null, null, null));
        org.junit.jupiter.api.Assertions.assertEquals(
                ru.lct.heatnetwork.jobs.JobCalculationException.Kind.RESULT_EXPIRED,
                org.junit.jupiter.api.Assertions.assertThrows(ru.lct.heatnetwork.jobs.JobCalculationException.class,
                        () -> service.openDownload(id)).getKind());
    }

    private static Job job(UUID id, Job.Status status) {
        return new Job(id, status, null, Instant.EPOCH, Instant.EPOCH, 0, 0,
                Map.of(), null, null, null, null);
    }
}
