package tn.esprit.marketsim.user.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ActivityLogServiceTest {

    private final ActivityLogService service = new ActivityLogService(
            mock(ActivityLogRepository.class),
            mock(ActivityLogWriter.class));

    @Test
    void rejectsInvertedDateRange() {
        Instant from = Instant.parse("2026-10-20T12:00:00Z");
        Instant to = Instant.parse("2026-10-19T12:00:00Z");
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, from, to, 0, 20));
    }

    @Test
    void rejectsInvalidPageSize() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, null, 0, 101));
    }

    @Test
    void traceDetailsSkipsUnchangedAndMarksPassword() {
        TraceDetails details = TraceDetails.empty()
                .add("email", "a@x.com", "a@x.com")
                .add("email", "a@x.com", "b@x.com")
                .passwordChanged(true);
        assertEquals(1, details.changes().size());
        assertEquals("email", details.changes().get(0).field());
        assertTrue(details.passwordChanged());
    }
}
