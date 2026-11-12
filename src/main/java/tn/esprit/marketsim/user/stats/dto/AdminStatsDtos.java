package tn.esprit.marketsim.user.stats.dto;

import java.time.Instant;
import java.util.Map;

public final class AdminStatsDtos {

    private AdminStatsDtos() {
    }

    public record AdminStatsResponse(
            int windowDays,
            Instant generatedAt,
            UserStats users,
            LoginStats logins,
            AdminActionStats adminActions,
            AnomalyStats anomalies) {
    }

    /** Etat actuel des comptes, sauf newInWindow (comptes crees pendant la fenetre). */
    public record UserStats(
            long total,
            long enabled,
            long disabled,
            long lockedNow,
            long newInWindow,
            Map<String, Long> byRole,
            Map<String, Long> byLevel) {
    }

    /** blockedWhileLocked : tentatives refusees parce que le compte etait deja verrouille. */
    public record LoginStats(long success, long failure, long blockedWhileLocked) {
    }

    public record AdminActionStats(long setEnabled, long deleteUser, long deleteRefused, long settingsUpdates) {
    }

    public record AnomalyStats(long assessed, long insufficientHistory, Map<String, Long> byRiskLevel) {
    }
}
