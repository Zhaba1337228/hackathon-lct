package ru.lct.heatnetwork.spatial;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatnetwork.projection.CrsTransform;

/** PostGIS-backed, paged map reads. It never loads the full export into heap memory. */
@Repository
public class PostgisMapViewStore implements MapViewStore {
    private static final int INSERT_BATCH_SIZE = 500;
    private static final String INSERT_RESULT_SQL = "INSERT INTO job_result_feature "
            + "(job_id,variant_id,id_kind,id_value,object_type,properties,geom) "
            + "VALUES (?,?,?,?,?,?::jsonb,ST_GeomFromText(?,32637))";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CrsTransform crsTransform;
    private final GeoJsonReader geoJsonReader = new GeoJsonReader();
    private final GeoJsonWriter geoJsonWriter = new GeoJsonWriter();
    private final WKBReader wkbReader = new WKBReader();
    private final WKBWriter wkbWriter = new WKBWriter(2);
    private final WKTWriter wktWriter = new WKTWriter(2);

    public PostgisMapViewStore(JdbcTemplate jdbc, ObjectMapper mapper, CrsTransform crsTransform) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.crsTransform = crsTransform;
    }

    @Override
    public Optional<Envelope> findProjectedInputExtent(UUID jobId) {
        String sql = "SELECT ST_XMin(extent::box3d),ST_YMin(extent::box3d),"
                + "ST_XMax(extent::box3d),ST_YMax(extent::box3d) "
                + "FROM (SELECT ST_Extent(geom) AS extent FROM job_feature WHERE job_id=?) bounds";
        List<Envelope> rows = jdbc.query(sql, (result, row) -> {
            if (result.getObject(1) == null) return null;
            return new Envelope(result.getDouble(1), result.getDouble(3),
                    result.getDouble(2), result.getDouble(4));
        }, jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    @Override
    public MapFeaturePage findInputPage(UUID jobId, Geometry projectedViewport, String cursor, int limit) {
        return queryPage("job_feature", jobId, null, projectedViewport, cursor, limit);
    }

    @Override
    public MapFeaturePage findVariantPage(UUID jobId, String variantId, Geometry projectedViewport,
            String cursor, int limit) {
        if (variantId == null || variantId.trim().isEmpty()) {
            throw new IllegalArgumentException("variantId is required");
        }
        return queryPage("job_result_feature", jobId, variantId, projectedViewport, cursor, limit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceVariantFeatures(UUID jobId, Path geoJsonFile) throws IOException {
        if (jobId == null || geoJsonFile == null) throw new IllegalArgumentException("jobId and geoJsonFile are required");
        jdbc.update("DELETE FROM job_result_feature WHERE job_id=?", jobId);
        List<ResultFeature> batch = new ArrayList<>(INSERT_BATCH_SIZE);
        try (InputStream input = Files.newInputStream(geoJsonFile);
                JsonParser parser = mapper.getFactory().createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected a GeoJSON FeatureCollection object");
            }
            boolean featuresFound = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException("Expected a FeatureCollection field");
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                if ("features".equals(field)) {
                    if (featuresFound || token != JsonToken.START_ARRAY) {
                        throw new IOException("Expected one GeoJSON features array");
                    }
                    featuresFound = true;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode feature = mapper.readTree(parser);
                        ResultFeature result = readResultFeature(feature);
                        if (result != null) {
                            batch.add(result);
                            if (batch.size() == INSERT_BATCH_SIZE) flush(jobId, batch);
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (!featuresFound || parser.nextToken() != null) {
                throw new IOException("Expected a complete GeoJSON FeatureCollection");
            }
            flush(jobId, batch);
        } catch (ParseException | IllegalArgumentException exception) {
            throw new IOException("Cannot index a calculated GeoJSON feature", exception);
        }
    }

    @Override
    public void deleteVariantFeatures(UUID jobId) {
        jdbc.update("DELETE FROM job_result_feature WHERE job_id=?", jobId);
    }

    private MapFeaturePage queryPage(String table, UUID jobId, String variantId, Geometry viewport,
            String cursor, int limit) {
        if (jobId == null || viewport == null || viewport.isEmpty()
                || viewport.getSRID() != CrsTransform.PROJECTED_SRID || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("A job, projected viewport and page limit from 1 to 500 are required");
        }
        Cursor after = decodeCursor(cursor);
        byte[] polygon = wkbWriter.write(viewport);
        StringBuilder sql = new StringBuilder("SELECT id_kind,id_value,object_type,properties::text,ST_AsBinary(geom) ")
                .append("FROM ").append(table).append(" WHERE job_id=? ")
                .append("AND geom && ST_GeomFromWKB(?,32637) ")
                .append("AND ST_Intersects(geom,ST_GeomFromWKB(?,32637)) ");
        List<Object> args = new ArrayList<>();
        args.add(jobId);
        args.add(polygon);
        args.add(polygon);
        if (variantId != null) {
            sql.append("AND variant_id=? ");
            args.add(variantId);
        }
        if (after != null) {
            sql.append("AND (id_kind>? OR (id_kind=? AND id_value>?)) ");
            args.add(after.kind);
            args.add(after.kind);
            args.add(after.value);
        }
        sql.append("ORDER BY id_kind,id_value LIMIT ?");
        args.add(limit + 1);
        List<Row> rows = jdbc.query(sql.toString(), rowMapper(), args.toArray());
        boolean hasMore = rows.size() > limit;
        List<Row> returned = hasMore ? new ArrayList<>(rows.subList(0, limit)) : rows;
        List<com.fasterxml.jackson.databind.JsonNode> features = new ArrayList<>(returned.size());
        for (Row row : returned) features.add(toGeoJsonFeature(row, variantId == null ? "input" : "variant"));
        String next = hasMore && !returned.isEmpty()
                ? encodeCursor(returned.get(returned.size() - 1).idKind, returned.get(returned.size() - 1).idValue)
                : null;
        return new MapFeaturePage(features, next);
    }

    private RowMapper<Row> rowMapper() {
        return (result, row) -> new Row(result.getString(1), result.getString(2), result.getString(3),
                result.getString(4), result.getBytes(5));
    }

    private com.fasterxml.jackson.databind.JsonNode toGeoJsonFeature(Row row, String layer) {
        try {
            Geometry projected = wkbReader.read(row.geometry);
            projected.setSRID(CrsTransform.PROJECTED_SRID);
            Geometry geographic = crsTransform.toGeographic(projected);
            ObjectNode feature = mapper.createObjectNode();
            feature.put("type", "Feature");
            JsonNode propertiesNode = mapper.readTree(row.properties);
            if (!(propertiesNode instanceof ObjectNode)) throw new IllegalStateException("Feature properties must be an object");
            ObjectNode properties = (ObjectNode) propertiesNode;
            properties.put("view_layer", layer);
            feature.set("properties", properties);
            feature.set("geometry", mapper.readTree(geoJsonWriter.write(geographic)));
            return feature;
        } catch (IOException | ParseException exception) {
            throw new IllegalStateException("Cannot serialize a projected map feature", exception);
        }
    }

    private ResultFeature readResultFeature(JsonNode feature) throws IOException, ParseException {
        if (feature == null || !feature.isObject() || !"Feature".equals(text(feature.get("type")))) {
            throw new IOException("Expected a GeoJSON Feature");
        }
        JsonNode propertiesNode = feature.get("properties");
        if (propertiesNode == null || !propertiesNode.isObject()) throw new IOException("Feature properties are required");
        String objectType = text(propertiesNode.get("object_type"));
        JsonNode geometryNode = feature.get("geometry");
        if ("variant_summary".equals(objectType)) return null;
        if (geometryNode == null || geometryNode.isNull()) return null;
        String variantId = text(propertiesNode.get("variant_id"));
        JsonNode idNode = propertiesNode.get("id");
        if (variantId == null || variantId.trim().isEmpty() || objectType == null
                || !(idNode != null && (idNode.isTextual() || idNode.isNumber()))) {
            throw new IOException("Calculated feature requires variant_id, object_type and a typed properties.id");
        }
        String idKind = idNode.isTextual() ? "string" : "number";
        String idValue = idNode.isTextual() ? idNode.textValue()
                : idNode.decimalValue().stripTrailingZeros().toPlainString();
        Geometry geographic = geoJsonReader.read(geometryNode.toString());
        geographic.setSRID(4326);
        Geometry projected = crsTransform.toProjected(geographic);
        return new ResultFeature(variantId, idKind, idValue, objectType,
                propertiesNode.toString(), wktWriter.write(projected));
    }

    private void flush(UUID jobId, List<ResultFeature> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(INSERT_RESULT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ResultFeature feature = rows.get(index);
                statement.setObject(1, jobId);
                statement.setString(2, feature.variantId);
                statement.setString(3, feature.idKind);
                statement.setString(4, feature.idValue);
                statement.setString(5, feature.objectType);
                statement.setString(6, feature.properties);
                statement.setString(7, feature.geometryWkt);
            }

            @Override
            public int getBatchSize() { return rows.size(); }
        });
        rows.clear();
    }

    private static String encodeCursor(String kind, String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (kind + "\n" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('\n');
            if (separator <= 0) throw new IllegalArgumentException("Invalid map page cursor");
            String kind = decoded.substring(0, separator);
            if (!"number".equals(kind) && !"string".equals(kind)) {
                throw new IllegalArgumentException("Invalid map page cursor type");
            }
            return new Cursor(kind, decoded.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid map page cursor", exception);
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static final class Cursor {
        private final String kind;
        private final String value;
        private Cursor(String kind, String value) { this.kind = kind; this.value = value; }
    }

    private static final class Row {
        private final String idKind;
        private final String idValue;
        private final String objectType;
        private final String properties;
        private final byte[] geometry;
        private Row(String idKind, String idValue, String objectType, String properties, byte[] geometry) {
            this.idKind = idKind; this.idValue = idValue; this.objectType = objectType;
            this.properties = properties; this.geometry = geometry;
        }
    }

    private static final class ResultFeature {
        private final String variantId;
        private final String idKind;
        private final String idValue;
        private final String objectType;
        private final String properties;
        private final String geometryWkt;
        private ResultFeature(String variantId, String idKind, String idValue, String objectType,
                String properties, String geometryWkt) {
            this.variantId = variantId; this.idKind = idKind; this.idValue = idValue;
            this.objectType = objectType; this.properties = properties; this.geometryWkt = geometryWkt;
        }
    }
}
