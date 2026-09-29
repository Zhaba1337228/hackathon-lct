package ru.lct.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.lct.heatnetwork.projection.CrsTransform;

class CalculateRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsWgs84BboxFromTheJsonRequestAndProjectsIt() throws Exception {
        CalculateRequest request = mapper.readValue(
                "{\"bbox\":[37.5,55.6,37.7,55.8]}", CalculateRequest.class);

        org.locationtech.jts.geom.Envelope area = request.toProjectedSearchArea(new CrsTransform());

        assertEquals(4, request.getBbox().length);
        org.junit.jupiter.api.Assertions.assertTrue(area.getWidth() > 0);
        org.junit.jupiter.api.Assertions.assertTrue(area.getHeight() > 0);
    }

    @Test
    void rejectsMissingOrInvalidBbox() {
        assertThrows(IllegalArgumentException.class,
                () -> new CalculateRequest().toProjectedSearchArea(new CrsTransform()));
        assertThrows(IllegalArgumentException.class,
                () -> new CalculateRequest(new double[] {37.5, 55.8, 37.7, 55.6})
                        .toProjectedSearchArea(new CrsTransform()));
    }

    @Test
    void rejectsNullAndStringCoordinatesInsteadOfCoercingThemToNumbers() throws Exception {
        CalculateRequest nullCoordinate = mapper.readValue(
                "{\"bbox\":[null,55.6,37.7,55.8]}", CalculateRequest.class);
        CalculateRequest stringCoordinate = mapper.readValue(
                "{\"bbox\":[\"37.5\",55.6,37.7,55.8]}", CalculateRequest.class);

        assertThrows(IllegalArgumentException.class,
                () -> nullCoordinate.toProjectedSearchArea(new CrsTransform()));
        assertThrows(IllegalArgumentException.class,
                () -> stringCoordinate.toProjectedSearchArea(new CrsTransform()));
    }

    @Test
    void copiesTheRequestArrayToPreventCallersChangingTheValidatedBounds() {
        double[] bbox = {37.5, 55.6, 37.7, 55.8};
        CalculateRequest request = new CalculateRequest(bbox);
        bbox[0] = 0;
        double[] read = request.getBbox();
        read[0] = 1;

        assertEquals(37.5, request.getBbox()[0]);
    }
}
