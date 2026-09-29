package ru.lct.heatnetwork.domain;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** GeoJSON properties with typed identifiers and pass-through extension fields. */
public final class FeatureProperties {
    private TypedId id;
    private String objectType;
    private final Map<String, JsonNode> extra = new LinkedHashMap<>();

    public FeatureProperties() {
    }

    public FeatureProperties(TypedId id, String objectType) {
        this.id = id;
        this.objectType = objectType;
    }

    @JsonProperty("id")
    public TypedId getId() {
        return id;
    }

    @JsonProperty("id")
    public void setId(TypedId id) {
        this.id = id;
    }

    @JsonProperty("object_type")
    public String getObjectType() {
        return objectType;
    }

    @JsonProperty("object_type")
    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    @JsonAnySetter
    public void putExtra(String name, JsonNode value) {
        extra.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, JsonNode> getExtra() {
        return Collections.unmodifiableMap(extra);
    }
}
