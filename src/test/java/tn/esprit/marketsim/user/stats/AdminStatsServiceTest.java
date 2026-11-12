package tn.esprit.marketsim.user.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogRepository;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyAssessmentRepository;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyStatus;
import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;
import tn.esprit.marketsim.user.stats.dto.AdminStatsDtos.AdminStatsResponse;

@ExtendWith(MockitoExtension.class)
class AdminStatsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private ActivityLogRepository activityLogRepository;
    @Mock
    private LoginAnomalyAssessmentRepository anomalyRepository;
    @InjectMocks
    private AdminStatsService service;

    @Test
    void rejectsWindowOutsideOneToNinetyDays() {
        assertThrows(IllegalArgumentException.class, () -> service.stats(0, NOW));
        assertThrows(IllegalArgumentException.class, () -> service.stats(91, NOW));
        verifyNoInteractions(userRepository, activityLogRepository, anomalyRepository);
    }

    @Test
    void aggregatesCountsOverTheRequestedWindowOnly() {
        Instant since = NOW.minus(Duration.ofDays(7));
        when(userRepository.countByEnabled(true)).thenReturn(4L);
        when(userRepository.countByEnabled(false)).thenReturn(1L);
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(1L);
        when(userRepository.countByRole(Role.USER)).thenReturn(4L);
        lenient().when(userRepository.countByLevel(any(UserLevel.class))).thenReturn(0L);
        when(userRepository.countByLevel(UserLevel.DEBUTANT)).thenReturn(5L);
        when(userRepository.countByLockedUntilAfter(any())).thenReturn(1L);
        when(userRepository.countByCreatedAtGreaterThanEqual(any())).thenReturn(2L);
        lenient().when(activityLogRepository.countByActionAndOutcomeAndOccurredAtGreaterThanEqual(
                any(), any(), any())).thenReturn(0L);
        when(activityLogRepository.countByActionAndOutcomeAndOccurredAtGreaterThanEqual(
                ActivityAction.LOGIN, ActivityOutcome.SUCCESS, since)).thenReturn(3L);
        when(activityLogRepository.countByActionAndOutcomeAndOccurredAtGreaterThanEqual(
                ActivityAction.LOGIN, ActivityOutcome.FAILURE, since)).thenReturn(2L);
        when(activityLogRepository.countByActionAndOutcomeAndOccurredAtGreaterThanEqual(
                ActivityAction.ADMIN_DELETE_USER, ActivityOutcome.FAILURE, since)).thenReturn(1L);
        when(activityLogRepository.countByActionAndReasonCodeAndOccurredAtGreaterThanEqual(
                ActivityAction.LOGIN, "ACCOUNT_LOCKED", since)).thenReturn(1L);
        lenient().when(anomalyRepository.countByStatusAndRiskLevelAndOccurredAtGreaterThanEqual(
                any(), any(), any())).thenReturn(0L);
        when(anomalyRepository.countByStatusAndRiskLevelAndOccurredAtGreaterThanEqual(
                LoginAnomalyStatus.ASSESSED, LoginRiskLevel.HIGH, since)).thenReturn(1L);
        when(anomalyRepository.countByStatusAndOccurredAtGreaterThanEqual(
                LoginAnomalyStatus.ASSESSED, since)).thenReturn(6L);
        when(anomalyRepository.countByStatusAndOccurredAtGreaterThanEqual(
                LoginAnomalyStatus.INSUFFICIENT_HISTORY, since)).thenReturn(4L);

        AdminStatsResponse stats = service.stats(7, NOW);

        assertEquals(7, stats.windowDays());
        assertEquals(NOW, stats.generatedAt());
        assertEquals(5, stats.users().total());
        assertEquals(1, stats.users().disabled());
        assertEquals(1, stats.users().lockedNow());
        assertEquals(2, stats.users().newInWindow());
        assertEquals(1L, stats.users().byRole().get("ADMIN"));
        assertEquals(5L, stats.users().byLevel().get("DEBUTANT"));
        assertEquals(4, stats.users().byLevel().size());
        assertEquals(3, stats.logins().success());
        assertEquals(2, stats.logins().failure());
        assertEquals(1, stats.logins().blockedWhileLocked());
        assertEquals(1, stats.adminActions().deleteRefused());
        assertEquals(0, stats.adminActions().deleteUser());
        assertEquals(6, stats.anomalies().assessed());
        assertEquals(4, stats.anomalies().insufficientHistory());
        assertEquals(1L, stats.anomalies().byRiskLevel().get("HIGH"));
        assertEquals(3, stats.anomalies().byRiskLevel().size());
        // Lecture seule : aucune ecriture.
        verify(userRepository, never()).save(any());
        verify(activityLogRepository, never()).save(any());
        verify(anomalyRepository, never()).save(any());
    }
}
