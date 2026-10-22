package tn.esprit.marketsim.user.anomaly;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.user.activity.ActivityLogRepository;

class LoginAnomalyServiceTest {

    private final LoginAnomalyService service = new LoginAnomalyService(
            mock(LoginAnomalyAssessmentRepository.class),
            mock(ActivityLogRepository.class),
            mock(LoginFeatureExtractor.class),
            mock(LoginRequestContext.class));

    @Test
    void rejectsInvertedDateRangeAndBadPagination() {
        Instant from = Instant.parse("2026-10-20T12:00:00Z");
        Instant to = Instant.parse("2026-10-19T12:00:00Z");
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, from, to, 0, 20));
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, null, null, -1, 20));
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, null, null, 0, 101));
    }
}
