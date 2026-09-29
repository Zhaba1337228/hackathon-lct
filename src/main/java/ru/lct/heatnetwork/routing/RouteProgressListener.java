package ru.lct.heatnetwork.routing;

import org.locationtech.jts.geom.Point;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;

/** Optional observer for completed, independently validated single-target searches. */
public interface RouteProgressListener {
    RouteProgressListener NONE = (request, candidate) -> { };

    void onAttemptCompleted(RouteRequest request, RouteCandidate candidate);

    default void onCombinationStarted() { }
    default void onTreeGrowthStarted() { }
    default void onTreeTrajectoryUpdated(String trajectoryId, Variant checkedTree) { }
    default void onTreeAttemptCompleted(TypedId targetId, String attemptId, Point destination,
            RouteCandidate.Status status, String reason, Double lengthM) { }
}
