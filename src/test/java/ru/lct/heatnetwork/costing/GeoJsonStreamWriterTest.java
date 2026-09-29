package ru.lct.heatnetwork.costing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.output.GeoJsonExportTooLargeException;
import ru.lct.heatnetwork.output.GeoJsonStreamWriter;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.validation.ValidationReport;

class GeoJsonStreamWriterTest {
    private static final GeometryFactory PROJECTED = new GeometryFactory(new PrecisionModel(), 32637);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesContractForAllVariantsWithTypedRefsAndResolvableGeometry() throws Exception {
        UUID jobId = UUID.fromString("d72ca280-c677-42fd-9ac0-04318a13003e");
        String collidingSourceId = "lct:heat-network:" + jobId + ":v1:line:0";
        List<FeatureIndex.IndexedFeature> inputs = inputs(collidingSourceId);
        List<CostedVariant> variants = Arrays.asList(costed("route-a", 1), costed("route-b", 2));
        GeoJsonStreamWriter writer = new GeoJsonStreamWriter(mapper, new CrsTransform(), 1_000_000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long bytes = writer.write(output, jobId, variants, inputs);
        JsonNode collection = mapper.readTree(output.toByteArray());

        assertEquals(bytes, output.size());
        assertTrue(bytes < 1_000_000);
        assertEquals("FeatureCollection", collection.path("type").asText());
        assertEquals(12, collection.path("features").size());

        Set<String> outputIds = new HashSet<>();
        Set<String> objectTypes = new HashSet<>();
        Map<String, JsonNode> exportedNodeCoordinates = new LinkedHashMap<>();
        Map<String, BigDecimal> lineLengthsByVariant = new LinkedHashMap<>();
        int summaries = 0;
        int lineCount = 0;
        int chamberCount = 0;
        int technicalCount = 0;
        JsonNode numericTarget = mapper.readTree("9007199254740994");
        JsonNode textualTarget = mapper.readTree("\"9007199254740994\"");

        for (JsonNode feature : collection.path("features")) {
            JsonNode properties = feature.path("properties");
            String objectType = properties.path("object_type").asText();
            objectTypes.add(objectType);
            JsonNode id = properties.path("id");
            assertTrue(id.isTextual(), "generated feature IDs use the string namespace");
            assertTrue(outputIds.add(id.toString()), "Feature IDs must be unique across variants");
            assertTrue(properties.path("variant_id").isTextual());

            if ("heat_network".equals(objectType)) {
                lineCount++;
                assertTrue(properties.path("length").isNumber() && properties.path("length").doubleValue() > 0);
                assertTrue(properties.has("depth_start") && properties.get("depth_start").isNull());
                assertTrue(properties.has("depth_end") && properties.get("depth_end").isNull());
                assertTrue(properties.path("flow_tph").isNumber());
                assertTrue(properties.path("diameter").isIntegralNumber());
                assertTrue(properties.path("cost").isNumber());
                assertEquals(new BigDecimal("100.25"), properties.path("cost").decimalValue());
                lineLengthsByVariant.merge(properties.path("variant_id").asText(),
                        properties.path("length").decimalValue(), BigDecimal::add);
                assertTrue(properties.path("start_node_id").isNumber()
                        || properties.path("start_node_id").isTextual());
                assertTrue(properties.path("end_node_id").isNumber()
                        || properties.path("end_node_id").isTextual());
            } else if ("heat_chamber".equals(objectType)) {
                chamberCount++;
                assertEquals(250, properties.path("diameter").asInt(), "new chamber includes existing network DN");
                assertTrue(properties.path("cost").isNumber());
                exportedNodeCoordinates.put(id.toString(), feature.path("geometry").path("coordinates"));
            } else if ("technical_node".equals(objectType)) {
                technicalCount++;
                exportedNodeCoordinates.put(id.toString(), feature.path("geometry").path("coordinates"));
            } else if ("variant_summary".equals(objectType)) {
                summaries++;
                assertTrue(feature.path("geometry").isNull());
                assertEquals(0, new BigDecimal("200.0").compareTo(properties.path("new_network_length").decimalValue()));
                assertEquals(0, new BigDecimal("12345.67").compareTo(properties.path("construction_cost").decimalValue()));
                assertEquals(0, new BigDecimal("0.1234567").compareTo(properties.path("score").decimalValue()));
                assertEquals(0, new BigDecimal("200.0").compareTo(lineLengthsByVariant.get(properties.path("variant_id").asText())));
                assertEquals(2, properties.path("unconnected_oks_ids").size());
                assertEquals(numericTarget, properties.path("unconnected_oks_ids").get(0));
                assertEquals(textualTarget, properties.path("unconnected_oks_ids").get(1));
                for (String field : Arrays.asList("rank", "construction_cost", "chamber_construction_cost",
                        "existing_chamber_tie_in_count", "existing_chamber_tie_in_cost", "unconnected_penalty",
                        "calculated_cost", "new_network_length", "score")) {
                    assertTrue(properties.has(field), "missing summary field " + field);
                }
            } else {
                throw new AssertionError("Input-only object type was exported: " + objectType);
            }
        }

        assertEquals(Set.of("heat_network", "heat_chamber", "technical_node", "variant_summary"), objectTypes);
        assertEquals(6, lineCount);
        assertEquals(2, chamberCount);
        assertEquals(2, technicalCount);
        assertEquals(2, summaries);
        assertFalse(outputIds.contains(mapper.writeValueAsString(TypedId.of(collidingSourceId))));
        assertTrue(outputIds.stream().anyMatch(id -> id.contains(":line:0:1\"")),
                "generated line ID must move past an input-ID collision");

        for (JsonNode feature : collection.path("features")) {
            if (!"heat_network".equals(feature.path("properties").path("object_type").asText())) continue;
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            JsonNode startId = feature.path("properties").path("start_node_id");
            JsonNode endId = feature.path("properties").path("end_node_id");
            JsonNode startCoordinates = exportedNodeCoordinates.get(startId.toString());
            JsonNode endCoordinates = exportedNodeCoordinates.get(endId.toString());
            if (startCoordinates != null) assertEquals(startCoordinates, coordinates.get(0));
            if (endCoordinates != null) assertEquals(endCoordinates, coordinates.get(coordinates.size() - 1));
            for (JsonNode coordinate : coordinates) {
                assertTrue(coordinate.get(0).doubleValue() >= -180 && coordinate.get(0).doubleValue() <= 180);
                assertTrue(coordinate.get(1).doubleValue() >= -90 && coordinate.get(1).doubleValue() <= 90);
            }
        }
    }

    @Test
    void exportByteLimitCountsEncodedBytesAndDoesNotWritePastTheLimit() throws Exception {
        UUID jobId = UUID.randomUUID();
        GeoJsonStreamWriter unrestricted = new GeoJsonStreamWriter(mapper, new CrsTransform(), 10_000);
        ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        long exactBytes = unrestricted.write(baseline, jobId, Collections.emptyList(), Collections.emptyList());

        ByteArrayOutputStream exact = new ByteArrayOutputStream();
        long written = new GeoJsonStreamWriter(mapper, new CrsTransform(), exactBytes)
                .write(exact, jobId, Collections.emptyList(), Collections.emptyList());
        assertEquals(exactBytes, written);
        assertEquals(exactBytes, exact.size());

        ByteArrayOutputStream limited = new ByteArrayOutputStream();
        assertThrows(GeoJsonExportTooLargeException.class,
                () -> new GeoJsonStreamWriter(mapper, new CrsTransform(), exactBytes - 1)
                        .write(limited, jobId, Collections.emptyList(), Collections.emptyList()));
        assertTrue(limited.size() <= exactBytes - 1);
    }

    private List<FeatureIndex.IndexedFeature> inputs(String collidingSourceId) throws Exception {
        List<FeatureIndex.IndexedFeature> inputs = new ArrayList<>();
        inputs.add(input(TypedId.of(new BigDecimal("9007199254740993")), "oks_connection_point", "POINT (500100 6200000)"));
        inputs.add(input(TypedId.of("9007199254740993"), "oks_connection_point", "POINT (500100 6200100)"));
        inputs.add(input(TypedId.of(new BigDecimal("9007199254740994")), "oks_connection_point", "POINT (499900 6200000)"));
        inputs.add(input(TypedId.of("9007199254740994"), "oks_connection_point", "POINT (499900 6200100)"));
        FeatureIndex.IndexedFeature network = input(TypedId.of(collidingSourceId), "heat_network", "LINESTRING (499900 6200200, 500100 6200200)");
        ((InputFeature) network.getFeature()).getProperties().putExtra("diameter",
                com.fasterxml.jackson.databind.node.IntNode.valueOf(250));
        inputs.add(network);
        inputs.add(input(TypedId.of(new BigDecimal("42")), "heat_chamber", "POINT (500000 6200100)"));
        return inputs;
    }

    private static CostedVariant costed(String id, int rank) throws Exception {
        TypedId numericTargetId = TypedId.of(new BigDecimal("9007199254740993"));
        TypedId stringTargetId = TypedId.of("9007199254740993");
        TypedId networkId = TypedId.of("lct:heat-network:d72ca280-c677-42fd-9ac0-04318a13003e:v1:line:0");
        TypedId existingChamberId = TypedId.of(new BigDecimal("42"));
        List<Variant.Node> nodes = Arrays.asList(
                new Variant.Node("target-number", 500100, 6200000, "oks_connection_point", numericTargetId),
                new Variant.Node("technical", 500050, 6200000, "technical_node"),
                new Variant.Node("new-chamber", 500000, 6200000, "heat_chamber", networkId),
                new Variant.Node("target-string", 500100, 6200100, "oks_connection_point", stringTargetId),
                new Variant.Node("existing-chamber", 500000, 6200100, "heat_chamber", existingChamberId));
        List<Variant.Segment> segments = Arrays.asList(
                segment("line-a", "target-number", "technical", "LINESTRING (500100 6200000, 500050 6200000)", 100),
                segment("line-b", "technical", "new-chamber", "LINESTRING (500050 6200000, 500000 6200000)", 150),
                segment("line-c", "target-string", "existing-chamber", "LINESTRING (500100 6200100, 500000 6200100)", 100));
        Variant variant = new Variant(id, nodes, segments);
        List<TypedId> unconnected = Arrays.asList(
                TypedId.of(new BigDecimal("9007199254740994")), TypedId.of("9007199254740994"));
        VariantCostSummary summary = new VariantCostSummary(new BigDecimal("12345.67"),
                new BigDecimal("456.78"), 1, new BigDecimal("5000000"), BigDecimal.ZERO,
                new BigDecimal("12345.67"), new BigDecimal("200.0"), new BigDecimal("0.1234567"), unconnected);
        Map<String, BigDecimal> segmentCosts = new LinkedHashMap<>();
        for (Variant.Segment segment : segments) segmentCosts.put(segment.getId(), new BigDecimal("100.25"));
        Map<String, BigDecimal> chamberCosts = new LinkedHashMap<>();
        chamberCosts.put("new-chamber", new BigDecimal("456.78"));
        return new CostedVariant(variant, new ValidationReport(Collections.emptyList(), Collections.emptyList()),
                summary, segmentCosts, chamberCosts);
    }

    private static Variant.Segment segment(String id, String start, String end, String wkt, int diameter)
            throws Exception {
        Geometry geometry = new WKTReader(PROJECTED).read(wkt);
        return new Variant.Segment(id, start, end, (org.locationtech.jts.geom.LineString) geometry,
                start, end, new BigDecimal("2.5"), diameter, "base", Collections.emptySet(), BigDecimal.ONE);
    }

    private static FeatureIndex.IndexedFeature input(TypedId id, String objectType, String wkt) throws Exception {
        FeatureProperties properties = new FeatureProperties(id, objectType);
        InputFeature feature = new InputFeature(properties, com.fasterxml.jackson.databind.node.NullNode.getInstance());
        Geometry geometry = new WKTReader(PROJECTED).read(wkt);
        return new FeatureIndex.IndexedFeature(feature, geometry);
    }
}
