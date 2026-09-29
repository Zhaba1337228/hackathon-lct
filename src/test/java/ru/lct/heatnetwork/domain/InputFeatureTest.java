package ru.lct.heatnetwork.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class InputFeatureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsPropertyIdTypeAndArbitraryAttributes() throws Exception {
        String input = "{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"oks_connection_point\",\"flow_tph\":2.5},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}";

        InputFeature feature = mapper.readValue(input, InputFeature.class);
        JsonNode output = mapper.readTree(mapper.writeValueAsString(feature));

        assertTrue(feature.getId().isNumeric());
        assertEquals("oks_connection_point", feature.getObjectType());
        assertEquals(1, output.at("/properties/id").intValue());
        assertFalse(output.has("id"));
        assertEquals(2.5, output.at("/properties/flow_tph").doubleValue());
        assertEquals("Point", output.at("/geometry/type").textValue());
    }

    @Test
    void preservesStringIdInThePropertiesObject() throws Exception {
        InputFeature feature = mapper.readValue(
                "{\"type\":\"Feature\",\"properties\":{\"id\":\"1\",\"object_type\":\"source\"},\"geometry\":null}",
                InputFeature.class);
        JsonNode output = mapper.readTree(mapper.writeValueAsString(feature));

        assertEquals("1", output.at("/properties/id").textValue());
        assertFalse(output.has("id"));
    }
}
