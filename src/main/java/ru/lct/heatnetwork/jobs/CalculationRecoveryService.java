package ru.lct.heatnetwork.jobs;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Marks process-local calculations interrupted by an application restart as terminal failures. */
@Service
public final class CalculationRecoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(CalculationRecoveryService.class);
    private static final String ERROR_CODE = "CALCULATION_INTERRUPTED";
    private static final String ERROR_MESSAGE =
            "Calculation was interrupted by a service restart; upload the dataset again to retry";

    private final JobRepository repository;
    private final Clock clock;

    @Autowired
    public CalculationRecoveryService(JobRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CalculationRecoveryService(JobRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedCalculations() {
        int recovered = repository.failInterruptedCalculations(ERROR_CODE, ERROR_MESSAGE, clock.instant());
        if (recovered > 0) {
            LOG.warn("Marked {} calculation(s) interrupted by the previous service process", recovered);
        }
    }
}
