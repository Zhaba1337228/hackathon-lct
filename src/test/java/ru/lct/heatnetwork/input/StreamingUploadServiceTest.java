package ru.lct.heatnetwork.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingUploadServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void storesEachValidatedInputInItsOwnServerGeneratedDirectory() throws Exception {
        StreamingUploadService service = service(1_000_000);
        byte[] fixture = Files.readAllBytes(Paths.get("src", "test", "resources", "fixtures",
                "two-targets-one-building.geojson"));

        UploadResult first = service.accept(new ByteArrayInputStream(fixture));
        UploadResult second = service.accept(new ByteArrayInputStream(fixture));

        assertNotEquals(first.getJobId(), second.getJobId());
        assertEquals(fixture.length, first.getReceivedBytes());
        assertTrue(Files.exists(jobInput(first.getJobId().toString())));
        assertTrue(Files.exists(jobInput(second.getJobId().toString())));
    }

    @Test
    void countsActualBytesAndDeletesOnlyTheRejectedJobsFiles() throws Exception {
        byte[] body = "1234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StreamingUploadService service = service(9);

        assertThrows(PayloadTooLargeException.class,
                () -> service.accept(new ByteArrayInputStream(body)));
        try (Stream<Path> children = Files.list(tempDirectory)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void removesTheJobInputWhenGeoJsonValidationFails() throws Exception {
        StreamingUploadService service = service(1_000_000);
        InputStream invalid = new ByteArrayInputStream("{\"type\":\"FeatureCollection\",\"features\":[{}]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(InputValidationException.class, () -> service.accept(invalid));
        try (Stream<Path> children = Files.list(tempDirectory)) {
            assertFalse(children.findAny().isPresent());
        }
    }

    private StreamingUploadService service(long limit) {
        return new StreamingUploadService(tempDirectory, limit,
                new GeoJsonStreamReader(new InputValidator(), new InMemoryFeatureIdRegistryFactory()));
    }

    private Path jobInput(String jobId) {
        return tempDirectory.resolve(jobId).resolve("input.geojson");
    }
}
