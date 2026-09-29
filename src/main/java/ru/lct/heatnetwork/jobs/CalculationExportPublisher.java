package ru.lct.heatnetwork.jobs;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import ru.lct.heatnetwork.costing.CostedVariant;
import ru.lct.heatnetwork.spatial.FeatureIndex;

/** Publishes a complete calculation export before the job can become SUCCEEDED. */
@FunctionalInterface
public interface CalculationExportPublisher {
    void publish(UUID jobId, List<CostedVariant> variants,
            List<FeatureIndex.IndexedFeature> inputs) throws IOException;
}
