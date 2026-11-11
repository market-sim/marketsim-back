package tn.esprit.marketsim.user.stats;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogRepository;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyAssessmentRepository;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyStatus;
import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;
import tn.esprit.marketsim.user.stats.dto.AdminStatsDtos.AdminActionStats;
import tn.esprit.marketsim.user.stats.dto.AdminStatsDtos.AdminStatsResponse;
import tn.esprit.marketsim.user.stats.dto.AdminStatsDtos.AnomalyStats;
import tn.esprit.marketsim.user.stats.dto.AdminStatsDtos.LoginStats;
import tn.esprit.marketsim.user.stats.dto.AdminStatsDtos.UserStats;

/** Statistiques du back-office, calculees uniquement a partir des donnees du module 1. Lecture seule. */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 90;

    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final LoginAnomalyAssessmentRepository anomalyRepository;

    @Transactional(readOnly = true)
    public AdminStatsResponse stats(int days) {
        return stats(days, Instant.now());
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse stats(int days, Instant now) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new IllegalArgumentException(
                    "La fenetre doit etre comprise entre " + MIN_DAYS + " et " + MAX_DAYS + " jours");
        }
        Instant since = now.minus(Duration.ofDays(days));
        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        LocalDateTime sinceLocal = LocalDateTime.ofInstant(since, ZoneId.systemDefault());

        long enabled = userRepository.countByEnabled(true);
        long disabled = userRepository.countByEnabled(false);
        Map<String, Long> byRole = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            byRole.put(role.name(), userRepository.countByRole(role));
        }
        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (UserLevel level : UserLevel.values()) {
            byLevel.put(level.name(), userRepository.countByLevel(level));
        }
        UserStats users = new UserStats(
                enabled + disabled,
                enabled,
                disabled,
                userRepository.countByLockedUntilAfter(nowLocal),
                userRepository.countByCreatedAtGreaterThanEqual(sinceLocal),
                byRole,
                byLevel);

        LoginStats logins = new LoginStats(
                count(ActivityAction.LOGIN, ActivityOutcome.SUCCESS, since),
                count(ActivityAction.LOGIN, ActivityOutcome.FAILURE, since),
                activityLogRepository.countByActionAndReasonCodeAndOccurredAtGreaterThanEqual(
                        ActivityAction.LOGIN, "ACCOUNT_LOCKED", since));

        AdminActionStats adminActions = new AdminActionStats(
                count(ActivityAction.ADMIN_SET_ENABLED, ActivityOutcome.SUCCESS, since),
                count(ActivityAction.ADMIN_DELETE_USER, ActivityOutcome.SUCCESS, since),
                count(ActivityAction.ADMIN_DELETE_USER, ActivityOutcome.FAILURE, since),
                count(ActivityAction.SETTINGS_UPDATE, ActivityOutcome.SUCCESS, since));

        Map<String, Long> byRisk = new LinkedHashMap<>();
        for (LoginRiskLevel risk : LoginRiskLevel.values()) {
            byRisk.put(risk.name(), anomalyRepository.countByStatusAndRiskLevelAndOccurredAtGreaterThanEqual(
                    LoginAnomalyStatus.ASSESSED, risk, since));
        }
        AnomalyStats anomalies = new AnomalyStats(
                anomalyRepository.countByStatusAndOccurredAtGreaterThanEqual(LoginAnomalyStatus.ASSESSED, since),
                anomalyRepository.countByStatusAndOccurredAtGreaterThanEqual(
                        LoginAnomalyStatus.INSUFFICIENT_HISTORY, since),
                byRisk);

        return new AdminStatsResponse(days, now, users, logins, adminActions, anomalies);
    }

    private long count(ActivityAction action, ActivityOutcome outcome, Instant since) {
        return activityLogRepository.countByActionAndOutcomeAndOccurredAtGreaterThanEqual(action, outcome, since);
    }
}
