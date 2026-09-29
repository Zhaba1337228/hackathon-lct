package ru.lct.heatnetwork.output;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnetwork.costing.CostedVariant;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.spatial.FeatureIndex;

/** Writes one bounded-memory GeoJSON FeatureCollection for every ranked variant. */
public final class GeoJsonStreamWriter {
    private static final double ENDPOINT_TOLERANCE_M = 0.01;

    private final ObjectMapper mapper;
    private final CrsTransform crsTransform;
    private final long maximumBytes;

    public GeoJsonStreamWriter(ObjectMapper mapper, CrsTransform crsTransform, long maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes must be positive");
        this.mapper = mapper;
        this.crsTransform = crsTransform;
        this.maximumBytes = maximumBytes;
    }

    /** Writes incrementally and returns the exact number of UTF-8 bytes written. */
    public long write(OutputStream output, UUID jobId, List<CostedVariant> variants,
            List<FeatureIndex.IndexedFeature> inputs) throws IOException {
        if (output == null || jobId == null || variants == null || inputs == null) {
            throw new IllegalArgumentException("output, jobId, variants and inputs are required");
        }
        LimitedOutputStream limited = new LimitedOutputStream(output, maximumBytes);
        Map<TypedId, FeatureIndex.IndexedFeature> inputById = indexInputs(inputs);
        Set<TypedId> reservedInputIds = new HashSet<>(inputById.keySet());
        Set<TypedId> emittedIds = new HashSet<>();
        Set<String> variantIds = new HashSet<>();

        try (JsonGenerator generator = mapper.getFactory().createGenerator(limited)) {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            generator.writeStartObject();
            generator.writeStringField("type", "FeatureCollection");
            generator.writeArrayFieldStart("features");

            int rank = 1;
            for (CostedVariant costed : variants) {
                if (costed == null || costed.getVariant() == null || costed.getSummary() == null) {
                    throw new IllegalArgumentException("Every exported variant must be costed and have a summary");
                }
                Variant variant = costed.getVariant();
                if (!variantIds.add(variant.getId())) {
                    throw new IllegalArgumentException("Variant IDs must be unique in one export");
                }
                Map<String, Variant.Node> nodesByInternalId = indexNodes(variant);
                Map<String, TypedId> outputNodeIds = mapOutputNodeIds(jobId, rank, variant,
                        inputById, reservedInputIds, emittedIds);

                for (Variant.Node node : variant.getNodes()) {
                    if (isNewChamber(node, inputById)) {
                        TypedId outputId = outputNodeIds.get(node.getId());
                        writeChamber(generator, outputId, variant.getId(), node, variant,
                                costed, inputById, emittedIds);
                    } else if ("technical_node".equals(node.getType())) {
                        TypedId outputId = outputNodeIds.get(node.getId());
                        writeTechnicalNode(generator, outputId, variant.getId(), node);
                    } else if (!"oks_connection_point".equals(node.getType())
                            && !"heat_chamber".equals(node.getType())) {
                        throw new IllegalArgumentException("Unsupported variant node type: " + node.getType());
                    }
                }

                int segmentIndex = 0;
                for (Variant.Segment segment : variant.getSegments()) {
                    Variant.Node start = nodesByInternalId.get(segment.getStartNodeId());
                    Variant.Node end = nodesByInternalId.get(segment.getEndNodeId());
                    if (start == null || end == null) throw new IllegalArgumentException("Segment endpoint is missing");
                    TypedId startId = outputNodeIds.get(start.getId());
                    TypedId endId = outputNodeIds.get(end.getId());
                    if (startId == null || endId == null) {
                        throw new IllegalArgumentException("Segment endpoint has no export ID");
                    }
                    TypedId featureId = generatedId(jobId, rank, "line", segmentIndex++,
                            reservedInputIds, emittedIds);
                    writeSegment(generator, featureId, variant.getId(), segment, startId, endId,
                            start, end, costed);
                }

                TypedId summaryId = generatedId(jobId, rank, "summary", 0, reservedInputIds, emittedIds);
                writeSummary(generator, summaryId, rank, variant.getId(), costed, inputById);
                rank++;
            }

            generator.writeEndArray();
            generator.writeEndObject();
            generator.flush();
        }
        return limited.getBytesWritten();
    }

    private static Map<TypedId, FeatureIndex.IndexedFeature> indexInputs(
            List<FeatureIndex.IndexedFeature> inputs) {
        Map<TypedId, FeatureIndex.IndexedFeature> result = new LinkedHashMap<>();
        for (FeatureIndex.IndexedFeature input : inputs) {
            if (input == null || input.getFeature() == null || input.getFeature().getId() == null) {
                throw new IllegalArgumentException("Export input has no typed feature ID");
            }
            if (result.put(input.getFeature().getId(), input) != null) {
                throw new IllegalArgumentException("Export input contains duplicate typed feature IDs");
            }
        }
        return result;
    }

    private static Map<String, Variant.Node> indexNodes(Variant variant) {
        Map<String, Variant.Node> result = new HashMap<>();
        for (Variant.Node node : variant.getNodes()) result.put(node.getId(), node);
        return result;
    }

    private static Map<String, TypedId> mapOutputNodeIds(UUID jobId, int rank, Variant variant,
            Map<TypedId, FeatureIndex.IndexedFeature> inputById, Set<TypedId> reservedInputIds,
            Set<TypedId> emittedIds) {
        Map<String, TypedId> result = new HashMap<>();
        int nodeIndex = 0;
        for (Variant.Node node : variant.getNodes()) {
            if ("oks_connection_point".equals(node.getType())
                    || isExistingChamber(node, inputById)) {
                if (node.getInputFeatureId() == null || !inputById.containsKey(node.getInputFeatureId())) {
                    throw new IllegalArgumentException("Input-backed node has no matching typed source ID");
                }
                String expectedType = "oks_connection_point".equals(node.getType())
                        ? "oks_connection_point" : "heat_chamber";
                if (!expectedType.equals(inputById.get(node.getInputFeatureId()).getFeature().getObjectType())) {
                    throw new IllegalArgumentException("Input-backed node source has the wrong object type");
                }
                result.put(node.getId(), node.getInputFeatureId());
            } else if ("technical_node".equals(node.getType()) || isNewChamber(node, inputById)) {
                result.put(node.getId(), generatedId(jobId, rank,
                        "technical_node".equals(node.getType()) ? "technical" : "chamber", nodeIndex++,
                        reservedInputIds, emittedIds));
            } else {
                throw new IllegalArgumentException("Unsupported variant node type: " + node.getType());
            }
        }
        return result;
    }

    private static boolean isExistingChamber(Variant.Node node,
            Map<TypedId, FeatureIndex.IndexedFeature> inputById) {
        if (!"heat_chamber".equals(node.getType()) || node.getInputFeatureId() == null) return false;
        FeatureIndex.IndexedFeature source = inputById.get(node.getInputFeatureId());
        return source != null && "heat_chamber".equals(source.getFeature().getObjectType());
    }

    private static boolean isNewChamber(Variant.Node node,
            Map<TypedId, FeatureIndex.IndexedFeature> inputById) {
        if (!"heat_chamber".equals(node.getType())) return false;
        if (node.getInputFeatureId() == null) return true;
        FeatureIndex.IndexedFeature source = inputById.get(node.getInputFeatureId());
        return source != null && "heat_network".equals(source.getFeature().getObjectType());
    }

    private static TypedId generatedId(UUID jobId, int rank, String kind, int index,
            Set<TypedId> reservedInputIds, Set<TypedId> emittedIds) {
        String base = "lct:heat-network:" + jobId + ":v" + rank + ":" + kind + ":" + index;
        int suffix = 0;
        TypedId candidate;
        do {
            candidate = TypedId.of(suffix == 0 ? base : base + ":" + suffix);
            suffix++;
        } while (reservedInputIds.contains(candidate) || emittedIds.contains(candidate));
        emittedIds.add(candidate);
        return candidate;
    }

    private void writeTechnicalNode(JsonGenerator generator, TypedId id, String variantId,
            Variant.Node node) throws IOException {
        writeFeatureStart(generator, id, "technical_node", variantId);
        generator.writeEndObject();
        writePointGeometry(generator, node.getX(), node.getY());
        generator.writeEndObject();
    }

    private void writeChamber(JsonGenerator generator, TypedId id, String variantId, Variant.Node node,
            Variant variant, CostedVariant costed, Map<TypedId, FeatureIndex.IndexedFeature> inputById,
            Set<TypedId> emittedIds) throws IOException {
        int maximumDiameter = 0;
        for (Variant.Segment segment : variant.getSegments()) {
            if (segment.getStartNodeId().equals(node.getId()) || segment.getEndNodeId().equals(node.getId())) {
                if (segment.getDiameterMm() == null) throw new IllegalArgumentException("Chamber-adjacent segment has no diameter");
                maximumDiameter = Math.max(maximumDiameter, segment.getDiameterMm());
            }
        }
        if (node.getInputFeatureId() != null) {
            FeatureIndex.IndexedFeature source = inputById.get(node.getInputFeatureId());
            if (source == null || !"heat_network".equals(source.getFeature().getObjectType())) {
                throw new IllegalArgumentException("New chamber source must be its existing heat network");
            }
            maximumDiameter = Math.max(maximumDiameter, integerProperty(source.getFeature(), "diameter"));
        }
        if (maximumDiameter <= 0) throw new IllegalArgumentException("New chamber has no adjacent pipe diameter");
        BigDecimal cost = costed.getChamberCostByNodeId().get(node.getId());
        if (cost == null) throw new IllegalArgumentException("New chamber is missing its calculated cost");
        if (!emittedIds.contains(id)) throw new IllegalArgumentException("New chamber ID was not reserved");

        writeFeatureStart(generator, id, "heat_chamber", variantId);
        generator.writeNumberField("diameter", maximumDiameter);
        writeMoney(generator, "cost", cost);
        generator.writeEndObject();
        writePointGeometry(generator, node.getX(), node.getY());
        generator.writeEndObject();
    }

    private void writeSegment(JsonGenerator generator, TypedId id, String variantId, Variant.Segment segment,
            TypedId startId, TypedId endId, Variant.Node start, Variant.Node end, CostedVariant costed)
            throws IOException {
        if (segment.getFlowTph() == null || segment.getDiameterMm() == null || segment.getLayingMethod() == null
                || (!"base".equals(segment.getLayingMethod()) && !"special".equals(segment.getLayingMethod()))) {
            throw new IllegalArgumentException("New heat network is missing required engineering properties");
        }
        BigDecimal cost = costed.getSegmentCostById().get(segment.getId());
        if (cost == null) throw new IllegalArgumentException("New heat network is missing its calculated cost");
        LineString projected = segment.getGeometry();
        if (projected == null || projected.getSRID() != CrsTransform.PROJECTED_SRID
                || projected.getNumPoints() < 2 || !Double.isFinite(projected.getLength())
                || projected.getLength() <= 0) {
            throw new IllegalArgumentException("New heat network requires a projected, positive-length LineString");
        }
        CoordinateSequence projectedCoordinates = projected.getCoordinateSequence();
        snapEndpoint(projectedCoordinates, 0, start);
        snapEndpoint(projectedCoordinates, projectedCoordinates.size() - 1, end);
        Geometry geographic = crsTransform.toGeographic(projected);
        if (!(geographic instanceof LineString) || geographic.getSRID() != 4326) {
            throw new IllegalStateException("LineString could not be transformed to WGS84");
        }
        double length = projected.getLength();
        if (!Double.isFinite(length)) throw new IllegalArgumentException("New heat network length is invalid");

        writeFeatureStart(generator, id, "heat_network", variantId);
        generator.writeFieldName("start_node_id");
        mapper.writeValue(generator, startId);
        generator.writeFieldName("end_node_id");
        mapper.writeValue(generator, endId);
        generator.writeNumberField("flow_tph", segment.getFlowTph());
        generator.writeNumberField("diameter", segment.getDiameterMm());
        generator.writeNumberField("length", BigDecimal.valueOf(length));
        generator.writeStringField("laying_method", segment.getLayingMethod());
        generator.writeNullField("depth_start");
        generator.writeNullField("depth_end");
        writeMoney(generator, "cost", cost);
        generator.writeEndObject();
        writeLineGeometry(generator, (LineString) geographic);
        generator.writeEndObject();
    }

    private static void snapEndpoint(CoordinateSequence sequence, int index, Variant.Node node) {
        double distance = Math.hypot(sequence.getX(index) - node.getX(), sequence.getY(index) - node.getY());
        if (distance > ENDPOINT_TOLERANCE_M) {
            throw new IllegalArgumentException("Heat network geometry endpoint does not match its referenced node");
        }
        sequence.setOrdinate(index, 0, node.getX());
        sequence.setOrdinate(index, 1, node.getY());
    }

    private void writePointGeometry(JsonGenerator generator, double x, double y) throws IOException {
        GeometryFactory factory = new GeometryFactory();
        Point projected = factory.createPoint(new org.locationtech.jts.geom.Coordinate(x, y));
        projected.setSRID(CrsTransform.PROJECTED_SRID);
        Point geographic = (Point) crsTransform.toGeographic(projected);
        CoordinateSequence coordinates = geographic.getCoordinateSequence();
        generator.writeFieldName("geometry");
        generator.writeStartObject();
        generator.writeStringField("type", "Point");
        generator.writeArrayFieldStart("coordinates");
        generator.writeNumber(coordinates.getX(0));
        generator.writeNumber(coordinates.getY(0));
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private static void writeLineGeometry(JsonGenerator generator, LineString line) throws IOException {
        CoordinateSequence coordinates = line.getCoordinateSequence();
        generator.writeFieldName("geometry");
        generator.writeStartObject();
        generator.writeStringField("type", "LineString");
        generator.writeArrayFieldStart("coordinates");
        for (int index = 0; index < coordinates.size(); index++) {
            generator.writeStartArray();
            generator.writeNumber(coordinates.getX(index));
            generator.writeNumber(coordinates.getY(index));
            generator.writeEndArray();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private static void writeFeatureStart(JsonGenerator generator, TypedId id, String objectType,
            String variantId) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", "Feature");
        generator.writeObjectFieldStart("properties");
        generator.writeFieldName("id");
        generator.writeObject(id);
        generator.writeStringField("object_type", objectType);
        generator.writeStringField("variant_id", variantId);
    }

    private void writeSummary(JsonGenerator generator, TypedId id, int rank, String variantId,
            CostedVariant costed, Map<TypedId, FeatureIndex.IndexedFeature> inputById) throws IOException {
        ru.lct.heatnetwork.costing.VariantCostSummary summary = costed.getSummary();
        writeFeatureStart(generator, id, "variant_summary", variantId);
        generator.writeNumberField("rank", rank);
        writeMoney(generator, "construction_cost", summary.getConstructionCost());
        writeMoney(generator, "chamber_construction_cost", summary.getChamberConstructionCost());
        generator.writeNumberField("existing_chamber_tie_in_count", summary.getExistingChamberTieInCount());
        writeMoney(generator, "existing_chamber_tie_in_cost", summary.getExistingChamberTieInCost());
        writeMoney(generator, "unconnected_penalty", summary.getUnconnectedPenalty());
        writeMoney(generator, "calculated_cost", summary.getCalculatedCost());
        generator.writeNumberField("new_network_length", summary.getNewNetworkLengthM());
        generator.writeNumberField("score", summary.getScore());
        generator.writeArrayFieldStart("unconnected_oks_ids");
        for (TypedId targetId : summary.getUnconnectedTargetIds()) {
            FeatureIndex.IndexedFeature input = inputById.get(targetId);
            if (input == null || !"oks_connection_point".equals(input.getFeature().getObjectType())) {
                throw new IllegalArgumentException("Unconnected summary IDs must reference input connection points");
            }
            mapper.writeValue(generator, targetId);
        }
        generator.writeEndArray();
        generator.writeArrayFieldStart("unsearched_oks_ids");
        for (TypedId targetId : summary.getUnsearchedTargetIds()) {
            FeatureIndex.IndexedFeature input = inputById.get(targetId);
            if (input == null || !"oks_connection_point".equals(input.getFeature().getObjectType())) {
                throw new IllegalArgumentException("Unsearched summary IDs must reference input connection points");
            }
            mapper.writeValue(generator, targetId);
        }
        generator.writeEndArray();
        generator.writeEndObject();
        generator.writeNullField("geometry");
        generator.writeEndObject();
    }

    private static void writeMoney(JsonGenerator generator, String field, BigDecimal value) throws IOException {
        if (value == null) throw new IllegalArgumentException("Missing required monetary value: " + field);
        generator.writeNumberField(field, value);
    }

    private static int integerProperty(InputFeature feature, String name) {
        com.fasterxml.jackson.databind.JsonNode value = feature.getProperties().getExtra().get(name);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Feature " + feature.getId() + " requires integer " + name);
        }
        return value.intValue();
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long bytesWritten;
        private LimitedOutputStream(OutputStream output, long limit) { super(output); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            ensureAllowed(1);
            out.write(value);
            bytesWritten++;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureAllowed(length);
            out.write(bytes, offset, length);
            bytesWritten += length;
        }
        private void ensureAllowed(int count) throws GeoJsonExportTooLargeException {
            if (count < 0 || bytesWritten > limit - count) throw new GeoJsonExportTooLargeException(limit);
        }
        private long getBytesWritten() { return bytesWritten; }
    }
}
