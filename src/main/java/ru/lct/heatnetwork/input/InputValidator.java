package ru.lct.heatnetwork.input;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;

/** Validates one Feature at a time; no FeatureCollection list is constructed. */
@Component
public final class InputValidator {
    private static final Set<Integer> DIAMETERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            50, 65, 80, 100, 125, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1200, 1400)));
    private static final Set<String> OBJECT_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "source", "heat_network", "heat_chamber", "oks_connection_point", "restriction")));
    private static final Set<String> RESTRICTION_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "oks", "park", "social_area", "prohibited_site", "water", "railway", "road", "tram_tracks",
            "gas_pipeline", "power_cable")));

    public ValidationContext newContext(FeatureIdRegistry idRegistry) {
        return new ValidationContext(idRegistry);
    }

    void validate(InputFeature feature, long index, ValidationContext context) {
        String featurePath = "features[" + index + "]";
        if (feature == null || !"Feature".equals(feature.getType())) {
            fail(null, featurePath + ".type", "Expected GeoJSON Feature");
        }
        FeatureProperties properties = feature.getProperties();
        if (properties == null) fail(null, featurePath + ".properties", "Required field is missing");
        TypedId id = properties.getId();
        String displayId = id == null ? null : id.toString();
        if (id == null) fail(null, featurePath + ".properties.id", "Required string or numeric ID is missing");
        try {
            context.idRegistry.register(id);
        } catch (DuplicateInputIdException exception) {
            fail(displayId, featurePath + ".properties.id", "Duplicate typed ID");
        }

        String objectType = properties.getObjectType();
        if (objectType == null || objectType.trim().isEmpty()) {
            fail(displayId, featurePath + ".properties.object_type", "Required field is missing");
        }
        if (!OBJECT_TYPES.contains(objectType)) {
            fail(displayId, featurePath + ".properties.object_type", "Unsupported object_type: " + objectType);
        }

        JsonNode geometryNode = feature.getGeometry();
        if (geometryNode == null || geometryNode.isNull()) {
            fail(displayId, featurePath + ".geometry", "Geometry is required");
        }
        String geometryType = text(geometryNode.get("type"));
        if (!isGeometryAllowed(objectType, geometryType)) {
            fail(displayId, featurePath + ".geometry.type", "Geometry type is not supported for " + objectType);
        }

        if ("heat_network".equals(objectType)) validateDiameter(properties, displayId, featurePath);
        if ("oks_connection_point".equals(objectType)) validateFlow(properties, displayId, featurePath);
        if ("restriction".equals(objectType)) validateRestriction(properties, geometryType, displayId, featurePath);

        Geometry geometry;
        try {
            geometry = new GeoJsonReader().read(geometryNode.toString());
        } catch (ParseException exception) {
            fail(displayId, featurePath + ".geometry", "Invalid geometry: " + exception.getMessage());
            return;
        }
        if (geometry.isEmpty() || geometry.getNumPoints() == 0) {
            fail(displayId, featurePath + ".geometry.coordinates", "Geometry must contain coordinates");
        }
        if (!geometry.isValid()) {
            fail(displayId, featurePath + ".geometry", "Geometry is topologically invalid");
        }
        for (Coordinate coordinate : geometry.getCoordinates()) {
            if (!Double.isFinite(coordinate.x) || !Double.isFinite(coordinate.y)
                    || coordinate.x < -180.0 || coordinate.x > 180.0
                    || coordinate.y < -90.0 || coordinate.y > 90.0) {
                fail(displayId, featurePath + ".geometry.coordinates", "Coordinate is outside CRS84 bounds");
            }
            if (!Double.isNaN(coordinate.getZ())) {
                fail(displayId, featurePath + ".geometry.coordinates", "Only 2D coordinates are supported");
            }
        }
        if (geometry.getLength() == 0.0 && ("LineString".equals(geometryType)
                || "MultiLineString".equals(geometryType))) {
            fail(displayId, featurePath + ".geometry.coordinates", "Line geometry must have positive length");
        }
    }

    private static void validateDiameter(FeatureProperties properties, String id, String path) {
        JsonNode value = properties.getExtra().get("diameter");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || !DIAMETERS.contains(value.intValue())) {
            fail(id, path + ".properties.diameter", "Expected a tabulated integer diameter");
        }
    }

    private static void validateFlow(FeatureProperties properties, String id, String path) {
        JsonNode value = properties.getExtra().get("flow_tph");
        if (value == null || !value.isNumber()) {
            fail(id, path + ".properties.flow_tph", "Expected a finite positive number");
        }
        BigDecimal decimal = value.decimalValue();
        if (decimal.signum() <= 0 || !Double.isFinite(decimal.doubleValue())) {
            fail(id, path + ".properties.flow_tph", "Expected a finite positive number");
        }
    }

    private static void validateRestriction(FeatureProperties properties, String geometryType, String id, String path) {
        JsonNode type = properties.getExtra().get("restriction_type");
        if (type == null || !type.isTextual() || !RESTRICTION_TYPES.contains(type.textValue())) {
            fail(id, path + ".properties.restriction_type", "Unknown or missing restriction_type");
        }
        String restrictionType = type.textValue();
        boolean linearRoad = ("road".equals(restrictionType) || "tram_tracks".equals(restrictionType))
                && ("LineString".equals(geometryType) || "MultiLineString".equals(geometryType));
        if (linearRoad) {
            JsonNode width = properties.getExtra().get("width_m");
            if (width == null || !width.isNumber() || !Double.isFinite(width.doubleValue())
                    || width.doubleValue() <= 0) {
                fail(id, path + ".properties.width_m", "Linear road/tram restrictions require a positive width_m");
            }
        }
    }

    private static boolean isGeometryAllowed(String objectType, String geometryType) {
        if ("source".equals(objectType) || "heat_chamber".equals(objectType)
                || "oks_connection_point".equals(objectType)) return "Point".equals(geometryType);
        if ("heat_network".equals(objectType)) return "LineString".equals(geometryType);
        return "restriction".equals(objectType) && Arrays.asList(
                "LineString", "MultiLineString", "Polygon", "MultiPolygon").contains(geometryType);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static void fail(String id, String path, String message) {
        throw new InputValidationException(id, path, message);
    }

    public static final class ValidationContext {
        private final FeatureIdRegistry idRegistry;

        private ValidationContext(FeatureIdRegistry idRegistry) {
            this.idRegistry = idRegistry;
        }
    }
}
