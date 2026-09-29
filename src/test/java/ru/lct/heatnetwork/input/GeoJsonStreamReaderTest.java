package ru.lct.heatnetwork.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GeoJsonStreamReaderTest {
    private final GeoJsonStreamReader reader = new GeoJsonStreamReader(
            new InputValidator(), new InMemoryFeatureIdRegistryFactory());

    @Test
    void readsFeaturesIncrementallyAndCountsObjectTypes() throws Exception {
        try (InputStream input = fixture("two-targets-one-building.geojson")) {
            GeoJsonStreamReader.Summary summary = reader.read(input);

            assertEquals(6, summary.getFeatureCount());
            assertEquals(2, summary.getCountsByType().get("oks_connection_point"));
            assertEquals(1, summary.getCountsByType().get("restriction"));
        }
    }

    @Test
    void deliversEachValidatedFeatureToConsumerWithoutBuildingACollection() throws Exception {
        AtomicLong delivered = new AtomicLong();
        try (InputStream input = fixture("two-targets-one-building.geojson")) {
            GeoJsonStreamReader.Summary summary = reader.read(input, feature -> delivered.incrementAndGet());
            assertEquals(summary.getFeatureCount(), delivered.get());
        }
    }

    @Test
    void acceptsOverlappingSpecialPassagesAsValidInput() throws Exception {
        try (InputStream input = fixture("special-overlap.geojson")) {
            assertEquals(3, reader.read(input).getFeatureCount());
        }
    }

    @Test
    void rejectsUnknownRestrictionWithFeatureAndFieldPath() throws Exception {
        InputValidationException error;
        try (InputStream input = fixture("invalid-unknown-restriction.geojson")) {
            error = assertThrows(InputValidationException.class, () -> reader.read(input));
        }

        assertEquals("unknown-1", error.getFeatureId());
        assertTrue(error.getFieldPath().endsWith("properties.restriction_type"));
    }

    @Test
    void rejectsLinearRoadWithoutDeclaredWidth() throws Exception {
        InputValidationException error;
        try (InputStream input = fixture("invalid-linear-road-no-width.geojson")) {
            error = assertThrows(InputValidationException.class, () -> reader.read(input));
        }

        assertTrue(error.getFieldPath().endsWith("properties.width_m"));
    }

    @Test
    void rejectsDiameterOutsideTheReferenceTable() throws Exception {
        InputValidationException error;
        try (InputStream input = fixture("invalid-unknown-diameter.geojson")) {
            error = assertThrows(InputValidationException.class, () -> reader.read(input));
        }

        assertTrue(error.getFieldPath().endsWith("properties.diameter"));
    }

    @Test
    void rejectsDuplicateNumericIdButDoesNotConfuseStringId() {
        String duplicate = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"source\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"source\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}]}";
        InputValidationException error = assertThrows(InputValidationException.class,
                () -> reader.read(new ByteArrayInputStream(duplicate.getBytes(StandardCharsets.UTF_8))));

        assertTrue(error.getFieldPath().endsWith("properties.id"));
    }

    private static InputStream fixture(String name) throws Exception {
        Path path = Path.of("src", "test", "resources", "fixtures", name);
        return Files.newInputStream(path);
    }
}
