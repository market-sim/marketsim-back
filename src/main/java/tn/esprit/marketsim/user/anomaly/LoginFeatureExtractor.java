package tn.esprit.marketsim.user.anomaly;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import tn.esprit.marketsim.user.activity.ActivityLog;
import tn.esprit.marketsim.user.activity.ActivityOutcome;

@Component
public class LoginFeatureExtractor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoginFeatureVector extract(
            Instant occurredAt,
            boolean loginFailed,
            String userAgent,
            String deviceHint,
            List<ActivityLog> priorLoginsForActor) {
        ZonedDateTime zdt = occurredAt.atZone(ZoneOffset.UTC);
        double hourAngle = 2 * Math.PI * zdt.getHour() / 24.0;
        double hourSin = Math.sin(hourAngle);
        double hourCos = Math.cos(hourAngle);
        double dayOfWeek = zdt.getDayOfWeek().getValue() / 7.0;

        Instant cutoff = occurredAt.minus(Duration.ofHours(24));
        int recentFailures = 0;
        int recentTotal = 0;
        Instant lastLoginAt = null;
        Set<String> knownBrowsers = new HashSet<>();

        for (ActivityLog log : priorLoginsForActor) {
            if (log.getOccurredAt() == null || !log.getOccurredAt().isBefore(occurredAt)) {
                // Strictement antérieur à la tentative courante (pas d'events futurs).
                continue;
            }
            if (lastLoginAt == null || log.getOccurredAt().isAfter(lastLoginAt)) {
                lastLoginAt = log.getOccurredAt();
            }
            String historicalBrowser = browserFamilyFromLog(log);
            if (historicalBrowser != null && !"unknown".equals(historicalBrowser)) {
                knownBrowsers.add(historicalBrowser);
            }
            if (!log.getOccurredAt().isBefore(cutoff)) {
                recentTotal++;
                if (log.getOutcome() == ActivityOutcome.FAILURE) {
                    recentFailures++;
                }
            }
        }

        Integer minutesSince = null;
        double minutesNorm = 1.0;
        if (lastLoginAt != null) {
            long minutes = Math.max(0, Duration.between(lastLoginAt, occurredAt).toMinutes());
            minutesSince = (int) Math.min(minutes, Integer.MAX_VALUE);
            minutesNorm = Math.min(1.0, minutes / (60.0 * 24.0 * 7.0));
        }

        double recentFailureRate = recentTotal == 0 ? 0.0 : (double) recentFailures / recentTotal;
        String browser = LoginAnomalyModel.normalizeBrowserFamily(userAgent);
        // Hint client non fiable : jamais utilise pour prouver une identite.
        double deviceNovelty = computeDeviceNovelty(browser, knownBrowsers, deviceHint);

        return new LoginFeatureVector(
                hourSin,
                hourCos,
                dayOfWeek,
                minutesNorm,
                recentFailureRate,
                loginFailed ? 1.0 : 0.0,
                deviceNovelty,
                browser,
                recentFailures,
                minutesSince);
    }

    private double computeDeviceNovelty(String browser, Set<String> knownBrowsers, String deviceHint) {
        double novelty;
        if ("unknown".equals(browser)) {
            novelty = 0.8;
        } else if (knownBrowsers.isEmpty()) {
            novelty = 0.45;
        } else if (knownBrowsers.contains(browser)) {
            novelty = 0.12;
        } else {
            novelty = 0.88;
        }
        if (!deviceHintSafe(deviceHint)) {
            novelty = Math.max(novelty, 0.55);
        }
        return novelty;
    }

    private String browserFamilyFromLog(ActivityLog log) {
        if (log.getChangesJson() == null || log.getChangesJson().isBlank()) {
            return null;
        }
        try {
            List<MapLikeChange> changes = objectMapper.readValue(log.getChangesJson(), new TypeReference<>() {
            });
            for (MapLikeChange change : changes) {
                if ("browserFamily".equals(change.field()) && change.after() != null) {
                    // after stocke deja la famille (chrome/firefox/...) ; on re-normalise defensivement.
                    return LoginAnomalyModel.normalizeBrowserFamily(change.after());
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /** Forme JSON des FieldChange du journal (sans dependre de la deserialisation record). */
    private record MapLikeChange(String field, String before, String after) {
    }

    private static boolean deviceHintSafe(String hint) {
        if (hint == null || hint.isBlank()) {
            return true;
        }
        String h = hint.toLowerCase(Locale.ROOT);
        return h.length() <= 120 && !h.contains("password") && !h.contains("token") && !h.contains("bearer");
    }
}
