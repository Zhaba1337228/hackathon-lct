package ru.lct.heatnetwork.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.lct.heatnetwork.spatial.FeatureIndex;

/** Projected input objects selected for independent validation of one candidate. */
public final class ValidationContext {
    private final List<FeatureIndex.IndexedFeature> features;

    public ValidationContext(List<FeatureIndex.IndexedFeature> features) {
        if (features == null) throw new IllegalArgumentException("Validation features are required");
        this.features = Collections.unmodifiableList(new ArrayList<>(features));
    }
    public List<FeatureIndex.IndexedFeature> getFeatures() { return features; }
}
