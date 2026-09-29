package ru.lct.heatnetwork.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Minimal Feature DTO matching the supplied properties.id GeoJSON schema. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class InputFeature {
    private String type = "Feature";
    private FeatureProperties properties;
    private JsonNode geometry;

    public InputFeature() {
    }

    public InputFeature(FeatureProperties properties, JsonNode geometry) {
        this.properties = properties;
        this.geometry = geometry;
    }

    @JsonProperty("type")
    public String getType() {
        return type;
    }

    @JsonProperty("type")
    public void setType(String type) {
        this.type = type;
    }

    @JsonProperty("properties")
    public FeatureProperties getProperties() {
        return properties;
    }

    @JsonProperty("properties")
    public void setProperties(FeatureProperties properties) {
        this.properties = properties;
    }

    @JsonProperty("geometry")
    public JsonNode getGeometry() {
        return geometry;
    }

    @JsonProperty("geometry")
    public void setGeometry(JsonNode geometry) {
        this.geometry = geometry;
    }

    @JsonIgnore
    public TypedId getId() {
        return properties == null ? null : properties.getId();
    }

    @JsonIgnore
    public String getObjectType() {
        return properties == null ? null : properties.getObjectType();
    }
}
