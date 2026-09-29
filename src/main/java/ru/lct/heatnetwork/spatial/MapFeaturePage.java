package ru.lct.heatnetwork.spatial;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded viewport page and the cursor for the next page. */
public final class MapFeaturePage {
    private final List<JsonNode> features;
    private final String nextCursor;

    public MapFeaturePage(List<JsonNode> features, String nextCursor) {
        this.features = Collections.unmodifiableList(new ArrayList<>(features));
        this.nextCursor = nextCursor;
    }

    public List<JsonNode> getFeatures() { return features; }
    public String getNextCursor() { return nextCursor; }
}
