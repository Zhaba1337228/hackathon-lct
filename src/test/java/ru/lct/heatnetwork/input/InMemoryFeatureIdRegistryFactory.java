package ru.lct.heatnetwork.input;

import java.util.HashSet;
import java.util.Set;
import ru.lct.heatnetwork.domain.TypedId;

final class InMemoryFeatureIdRegistryFactory implements FeatureIdRegistryFactory {
    @Override
    public FeatureIdRegistry create() {
        return new FeatureIdRegistry() {
            private final Set<TypedId> ids = new HashSet<>();

            @Override
            public void register(TypedId id) {
                if (!ids.add(id)) throw new DuplicateInputIdException();
            }

            @Override
            public void complete() { }

            @Override
            public void close() { ids.clear(); }
        };
    }
}
