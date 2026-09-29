package ru.lct.heatnetwork.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.projection.Wgs84BoundingBox;

class PostgisMapViewStoreTest {
    @TempDir Path temporary;

    @Test
    void streamsOnlyGeometryFeaturesAndPreservesTypedIdsWhenIndexingExport() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PostgisMapViewStore store = new PostgisMapViewStore(jdbc, new ObjectMapper(), new CrsTransform());
        UUID jobId = UUID.randomUUID();
        Path export = temporary.resolve("export.geojson");
        Files.writeString(export, "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"heat_network\",\"variant_id\":\"route-a\"},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6,55.7],[37.61,55.71]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"1\",\"object_type\":\"heat_chamber\",\"variant_id\":\"route-a\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.61,55.71]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"id\":2,\"object_type\":\"variant_summary\",\"variant_id\":\"route-a\"},\"geometry\":null}]} ");

        AtomicInteger indexedCount = new AtomicInteger();
        AtomicReference<PreparedStatement> statementReference = new AtomicReference<>(mock(PreparedStatement.class));
        doAnswer(invocation -> {
            BatchPreparedStatementSetter batch = invocation.getArgument(1);
            indexedCount.set(batch.getBatchSize());
            for (int index = 0; index < batch.getBatchSize(); index++) batch.setValues(statementReference.get(), index);
            return new int[batch.getBatchSize()];
        }).when(jdbc).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));

        store.replaceVariantFeatures(jobId, export);

        assertEquals(2, indexedCount.get(), "summary rows without geometry are not indexed");
        PreparedStatement statement = statementReference.get();
        verify(statement, org.mockito.Mockito.times(2)).setString(2, "route-a");
        verify(statement, org.mockito.Mockito.times(2)).setString(4, "1");
        verify(statement, org.mockito.Mockito.times(2)).setString(eq(7), anyString());
        org.mockito.ArgumentCaptor<String> idKinds = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(statement, org.mockito.Mockito.times(2)).setString(eq(3), idKinds.capture());
        assertEquals(java.util.Arrays.asList("number", "string"), idKinds.getAllValues());
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsOnlyIntersectingViewportRowsAsWgs84GeoJson() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        CrsTransform transform = new CrsTransform();
        Geometry source = new GeometryFactory().createPoint(new Coordinate(37.6, 55.7));
        Geometry projected = transform.toProjected(source);
        projected.setSRID(CrsTransform.PROJECTED_SRID);
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("number");
        when(row.getString(2)).thenReturn("17");
        when(row.getString(3)).thenReturn("oks_connection_point");
        when(row.getString(4)).thenReturn("{\"id\":17,\"object_type\":\"oks_connection_point\"}");
        when(row.getBytes(5)).thenReturn(new WKBWriter(2).write(projected));
        when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class), any(byte[].class), any(byte[].class), anyInt()))
                .thenAnswer(invocation -> {
            RowMapper<?> mapperArgument = invocation.getArgument(1);
            return Collections.singletonList(mapperArgument.mapRow(row, 0));
        });
        PostgisMapViewStore store = new PostgisMapViewStore(jdbc, mapper, transform);
        Geometry viewport = Wgs84BoundingBox.fromArray(new double[] {37.59, 55.69, 37.61, 55.71})
                .toProjectedPolygon(transform);

        MapFeaturePage page = store.findInputPage(UUID.randomUUID(), viewport, null, 10);

        assertEquals(1, page.getFeatures().size());
        assertEquals("input", page.getFeatures().get(0).path("properties").path("view_layer").asText());
        assertEquals(37.6, page.getFeatures().get(0).path("geometry").path("coordinates").get(0).asDouble(), 0.00001);
        assertEquals(55.7, page.getFeatures().get(0).path("geometry").path("coordinates").get(1).asDouble(), 0.00001);
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(UUID.class), any(byte[].class),
                any(byte[].class), org.mockito.ArgumentMatchers.eq(11));
        assertTrue(sql.getValue().contains("ST_Intersects"));
        assertTrue(sql.getValue().contains("job_id=?"));
        assertTrue(page.getFeatures().get(0).path("properties").path("id").isNumber());
    }
}
