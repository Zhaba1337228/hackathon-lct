package ru.lct.heatnetwork.spatial;

import ru.lct.heatnetwork.domain.TypedId;

public interface ClearanceRange {
    TypedId getRestrictionId();
    double getStartMeasureM();
    double getEndMeasureM();
}
