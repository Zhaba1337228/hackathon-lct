package ru.lct.heatnetwork.spatial;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.projection.CrsTransform;

/** PostGIS GiST index narrows candidates; exact corridor intersection is checked with JTS. */
@Repository
public class PostgisFeatureIndex implements FeatureIndex {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CrsTransform crsTransform;

    public PostgisFeatureIndex(JdbcTemplate jdbc, ObjectMapper mapper, CrsTransform crsTransform) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.crsTransform = crsTransform;
    }

    @Override
    public void store(UUID jobId, InputFeature feature) {
        Geometry geographic = readGeometry(feature);
        Geometry projected = crsTransform.toProjected(geographic);
        FeatureId id = FeatureId.of(feature.getId());
        try {
            jdbc.update("INSERT INTO job_feature(job_id,id_kind,id_value,object_type,properties,geom) "
                            + "VALUES (?,?,?, ?,?::jsonb,ST_GeomFromText(?,32637))",
                    jobId, id.kind, id.value, feature.getObjectType(), mapper.writeValueAsString(feature.getProperties()),
                    new WKTWriter(2).write(projected));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize feature properties", exception);
        }
    }

    @Override
    public List<IndexedFeature> findCandidates(UUID jobId, Geometry corridor) {
        if (corridor == null || corridor.isEmpty()) throw new IllegalArgumentException("corridor is required");
        if (corridor.getSRID() != CrsTransform.PROJECTED_SRID) {
            throw new IllegalArgumentException("corridor must use EPSG:32637");
        }
        Envelope envelope = corridor.getEnvelopeInternal();
        List<Row> candidates = jdbc.query(
                "SELECT id_kind,id_value,object_type,properties::text,ST_AsBinary(geom) "
                        + "FROM job_feature WHERE job_id=? AND geom && ST_MakeEnvelope(?,?,?,?,32637)",
                (row, index) -> new Row(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                        row.getBytes(5)), jobId, envelope.getMinX(), envelope.getMinY(),
                envelope.getMaxX(), envelope.getMaxY());
        return readRows(candidates, corridor);
    }

    @Override
    public List<IndexedFeature> findCalculationContext(UUID jobId, Geometry searchArea, double marginM) {
        if (searchArea == null || searchArea.isEmpty() || searchArea.getSRID() != CrsTransform.PROJECTED_SRID
                || !Double.isFinite(marginM) || marginM < 0) {
            throw new IllegalArgumentException("Calculation context requires a projected area and non-negative margin");
        }
        String wkt = new WKTWriter(2).write(searchArea);
        List<Row> candidates = jdbc.query(
                "SELECT id_kind,id_value,object_type,properties::text,ST_AsBinary(geom) "
                        + "FROM job_feature WHERE job_id=? AND (object_type='oks_connection_point' OR "
                        + "ST_DWithin(geom,ST_GeomFromText(?,32637),? + CASE "
                        + "WHEN object_type='restriction' AND ST_Dimension(geom)=1 "
                        + "AND jsonb_typeof(properties->'width_m')='number' "
                        + "THEN GREATEST((properties->>'width_m')::double precision,0)/2 ELSE 0 END))",
                (row, index) -> new Row(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                        row.getBytes(5)), jobId, wkt, marginM);
        return readRows(candidates, null);
    }

    private List<IndexedFeature> readRows(List<Row> candidates, Geometry exactFilter) {
        List<IndexedFeature> exact = new ArrayList<>();
        for (Row row : candidates) {
            try {
                Geometry candidate = new WKBReader(new GeometryFactory(new PrecisionModel(),
                        CrsTransform.PROJECTED_SRID)).read(row.geometry);
                candidate.setSRID(CrsTransform.PROJECTED_SRID);
                if (exactFilter == null || candidate.intersects(exactFilter)) {
                    exact.add(new IndexedFeature(toFeature(row, candidate), candidate));
                }
            } catch (ParseException | JsonProcessingException exception) {
                throw new IllegalStateException("Cannot read indexed feature", exception);
            }
        }
        return exact;
    }

    @Override
    public void deleteJob(UUID jobId) {
        jdbc.update("DELETE FROM job_result_feature WHERE job_id=?", jobId);
        jdbc.update("DELETE FROM job_feature WHERE job_id=?", jobId);
    }

    private Geometry readGeometry(InputFeature feature) {
        try {
            return new org.locationtech.jts.io.geojson.GeoJsonReader().read(feature.getGeometry().toString());
        } catch (ParseException exception) {
            throw new IllegalArgumentException("Validated feature geometry could not be parsed", exception);
        }
    }

    private InputFeature toFeature(Row row, Geometry geometry) throws JsonProcessingException {
        String featureJson = "{\"type\":\"Feature\",\"properties\":" + row.properties
                + ",\"geometry\":" + new GeoJsonWriter().write(geometry) + "}";
        InputFeature feature = mapper.readValue(featureJson, InputFeature.class);
        FeatureId id = FeatureId.of(feature.getId());
        if (!row.objectType.equals(feature.getObjectType()) || !row.idKind.equals(id.kind)
                || !row.idValue.equals(id.value)) {
            throw new IllegalStateException("Indexed feature identity mismatch");
        }
        return feature;
    }

    private static final class FeatureId {
        private final String kind;
        private final String value;
        private FeatureId(String kind, String value) { this.kind = kind; this.value = value; }
        private static FeatureId of(TypedId id) {
            return new FeatureId(id.isNumeric() ? "number" : "string",
                    id.isNumeric() ? id.asNumber().toPlainString() : id.asString());
        }
    }

    private static final class Row {
        private final String idKind, idValue, objectType, properties;
        private final byte[] geometry;
        private Row(String idKind, String idValue, String objectType, String properties, byte[] geometry) {
            this.idKind = idKind; this.idValue = idValue; this.objectType = objectType;
            this.properties = properties; this.geometry = geometry;
        }
    }
}
