package tn.esprit.marketsim.user.anomaly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLog;
import tn.esprit.marketsim.user.activity.ActivityOutcome;

class LoginFeatureExtractorTest {

    private final LoginFeatureExtractor extractor = new LoginFeatureExtractor();

    @Test
    void ignoresFutureEventsAndCountsRecentFailures() {
        Instant now = Instant.parse("2026-10-19T15:00:00Z");
        ActivityLog olderFail = ActivityLog.builder()
                .occurredAt(now.minus(2, ChronoUnit.HOURS))
                .action(ActivityAction.LOGIN)
                .outcome(ActivityOutcome.FAILURE)
                .reasonCode("INVALID_CREDENTIALS")
                .changesJson("[{\"field\":\"browserFamily\",\"before\":null,\"after\":\"chrome\"}]")
                .build();
        ActivityLog future = ActivityLog.builder()
                .occurredAt(now.plus(1, ChronoUnit.HOURS))
                .action(ActivityAction.LOGIN)
                .outcome(ActivityOutcome.FAILURE)
                .reasonCode("INVALID_CREDENTIALS")
                .build();

        LoginFeatureVector v = extractor.extract(now, true, "Mozilla/5.0 Chrome/120.0", null,
                List.of(olderFail, future));

        assertEquals(1, v.recentFailureCount());
        assertEquals("chrome", v.browserFamily());
        assertTrue(v.deviceNovelty() < 0.3, "expected familiar browser novelty, got " + v.deviceNovelty());
    }

    @Test
    void unfamiliarBrowserRaisesNovelty() {
        Instant now = Instant.parse("2026-10-19T15:00:00Z");
        ActivityLog prior = ActivityLog.builder()
                .occurredAt(now.minus(1, ChronoUnit.DAYS))
                .action(ActivityAction.LOGIN)
                .outcome(ActivityOutcome.SUCCESS)
                .reasonCode("OK")
                .changesJson("[{\"field\":\"browserFamily\",\"before\":null,\"after\":\"chrome\"}]")
                .build();

        LoginFeatureVector v = extractor.extract(now, false, "Mozilla/5.0 Firefox/121.0", null, List.of(prior));
        assertEquals("firefox", v.browserFamily());
        assertTrue(v.deviceNovelty() > 0.7);
    }
}
