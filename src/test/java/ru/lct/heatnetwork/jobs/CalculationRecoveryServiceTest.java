package ru.lct.heatnetwork.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.lct.heatnetwork.domain.Job;
import ru.lct.heatnetwork.projection.CrsTransform;

class CalculationRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void marksOnlyPersistedInFlightCalculationsAsInterruptedAtStartup() {
        JobRepository repository = mock(JobRepository.class);
        when(repository.failInterruptedCalculations("CALCULATION_INTERRUPTED",
                "Calculation was interrupted by a service restart; upload the dataset again to retry", NOW))
                .thenReturn(2);

        new CalculationRecoveryService(repository, CLOCK).recoverInterruptedCalculations();

        verify(repository).failInterruptedCalculations("CALCULATION_INTERRUPTED",
                "Calculation was interrupted by a service restart; upload the dataset again to retry", NOW);
    }

    @Test
    void resultReadRaceWithRetentionReportsExpiredInsteadOfUnavailable() {
        JobRepository repository = mock(JobRepository.class);
        UUID id = UUID.randomUUID();
        Job succeeded = job(id, null);
        Job purged = job(id, NOW);
        when(repository.findById(id)).thenReturn(Optional.of(succeeded), Optional.of(purged));
        when(repository.findCalculationResult(id)).thenReturn(Optional.empty());
        JobCalculationService service = new JobCalculationService(repository, null, null,
                new CrsTransform(), new ObjectMapper(), CLOCK);

        JobCalculationException error = assertThrows(JobCalculationException.class, () -> service.result(id));

        assertEquals(JobCalculationException.Kind.RESULT_EXPIRED, error.getKind());
    }

    private static Job job(UUID id, Instant purgedAt) {
        return new Job(id, Job.Status.SUCCEEDED, null, NOW, NOW, purgedAt, 10, 1,
                Collections.emptyMap(), null, null, null, null);
    }
}
