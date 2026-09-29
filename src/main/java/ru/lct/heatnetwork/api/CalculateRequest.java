package ru.lct.heatnetwork.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.locationtech.jts.geom.Envelope;
import ru.lct.heatnetwork.projection.CrsTransform;
import ru.lct.heatnetwork.projection.Wgs84BoundingBox;

@Schema(description = "Bounded calculation request")
public final class CalculateRequest {
    @Schema(description = "WGS84 degrees ordered [west longitude, south latitude, east longitude, north latitude]",
            example = "[37.5,55.6,37.7,55.8]", requiredMode = Schema.RequiredMode.REQUIRED)
    private JsonNode bbox;

    public CalculateRequest() { }

    public CalculateRequest(double[] bbox) {
        setBbox(bbox);
    }

    @JsonIgnore
    public double[] getBbox() {
        if (bbox == null || !bbox.isArray() || bbox.size() != 4) {
            throw new IllegalArgumentException("bbox must contain [west,south,east,north]");
        }
        double[] coordinates = new double[4];
        for (int index = 0; index < coordinates.length; index++) {
            JsonNode coordinate = bbox.get(index);
            if (coordinate == null || !coordinate.isNumber()) {
                throw new IllegalArgumentException("bbox coordinates must be JSON numbers");
            }
            coordinates[index] = coordinate.doubleValue();
            if (!Double.isFinite(coordinates[index])) {
                throw new IllegalArgumentException("bbox coordinates must be finite numbers");
            }
        }
        return coordinates;
    }

    @JsonIgnore
    public void setBbox(double[] bbox) {
        if (bbox == null) {
            this.bbox = null;
            return;
        }
        ArrayNode values = JsonNodeFactory.instance.arrayNode();
        for (double coordinate : bbox) values.add(DoubleNode.valueOf(coordinate));
        this.bbox = values;
    }

    @JsonProperty("bbox")
    public JsonNode getBboxValue() {
        return bbox == null ? null : bbox.deepCopy();
    }

    @JsonProperty("bbox")
    public void setBboxValue(JsonNode bbox) {
        this.bbox = bbox == null ? null : bbox.deepCopy();
    }

    public Envelope toProjectedSearchArea(CrsTransform crsTransform) {
        return Wgs84BoundingBox.fromArray(getBbox()).toProjectedEnvelope(crsTransform);
    }
}
