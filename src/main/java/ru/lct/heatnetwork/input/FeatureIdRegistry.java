package ru.lct.heatnetwork.input;

import ru.lct.heatnetwork.domain.TypedId;

public interface FeatureIdRegistry extends AutoCloseable {
    void register(TypedId id);
    void complete();
    @Override
    void close();
}
