package ru.lct.heatnetwork.jobs;

import java.util.UUID;
import ru.lct.heatnetwork.domain.Job;

/** Read-only access to persisted job state for result-serving components. */
@FunctionalInterface
public interface JobLookup {
    Job get(UUID id);
}
