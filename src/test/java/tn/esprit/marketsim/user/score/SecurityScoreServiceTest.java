package tn.esprit.marketsim.user.score;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyAssessment;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyAssessmentRepository;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyStatus;
import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;
import tn.esprit.marketsim.user.score.dto.SecurityScoreDtos.SecurityScoreResponse;

@ExtendWith(MockitoExtension.class)
class SecurityScoreServiceTest {

    @Mock
    private UserSecurityScoreRepository scoreRepository;
    @Mock
    private LoginAnomalyAssessmentRepository assessmentRepository;
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private SecurityScoreService service;

    @Test
    void ignoresNonAssessedAndNeedsThreeQualifyingRows() {
        when(scoreRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(assessmentRepository.findByActorId(7L)).thenReturn(List.of(
                assessed(LoginRiskLevel.HIGH),
                assessed(LoginRiskLevel.LOW),
                assessed(null),
                row(LoginAnomalyStatus.UNAVAILABLE, null),
                row(LoginAnomalyStatus.INSUFFICIENT_HISTORY, null)));

        SecurityScoreResponse first = service.recomputeOwn(7L);
        SecurityScoreResponse second = service.recomputeOwn(7L);

        assertTrue(first.insufficientEvidence());
        assertNull(first.score());
        assertNull(first.riskBand());
        assertEquals(2, first.assessmentCount());
        assertEquals(first.score(), second.score());
        assertEquals(first.assessmentCount(), second.assessmentCount());
        assertEquals(first.insufficientEvidence(), second.insufficientEvidence());
    }

    @Test
    void threeAssessedLoginsProduceStableScore() {
        when(scoreRepository.findByUserId(4L)).thenReturn(Optional.empty());
        when(assessmentRepository.findByActorId(4L)).thenReturn(List.of(
                assessed(LoginRiskLevel.LOW),
                assessed(LoginRiskLevel.LOW),
                assessed(LoginRiskLevel.LOW)));

        SecurityScoreResponse score = service.recomputeOwn(4L);
        assertEquals(33, score.score());
        assertEquals(LoginRiskLevel.LOW, score.riskBand());
        assertEquals(3, score.assessmentCount());
        assertEquals(false, score.insufficientEvidence());
    }

    @Test
    void missingUserWithoutHistoryIsNotFound() {
        when(userRepository.existsById(99L)).thenReturn(false);
        when(assessmentRepository.existsByActorId(99L)).thenReturn(false);
        when(scoreRepository.findByUserId(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.recomputeForAdmin(99L));
    }

    @Test
    void deletedUserKeepsStoredScoreWhenAssessmentsAreGone() {
        UserSecurityScore stored = UserSecurityScore.builder()
                .userId(8L)
                .score(44)
                .riskBand(LoginRiskLevel.MEDIUM)
                .assessmentCount(3)
                .insufficientEvidence(false)
                .computedAt(Instant.parse("2026-10-19T12:00:00Z"))
                .build();
        when(userRepository.existsById(8L)).thenReturn(false);
        when(assessmentRepository.existsByActorId(8L)).thenReturn(false);
        when(scoreRepository.findByUserId(8L)).thenReturn(Optional.of(stored));

        SecurityScoreResponse score = service.recomputeForAdmin(8L);
        assertEquals(44, score.score());
        assertEquals(LoginRiskLevel.MEDIUM, score.riskBand());
        verify(assessmentRepository, never()).findByActorId(any());
    }

    private static LoginAnomalyAssessment assessed(LoginRiskLevel level) {
        return row(LoginAnomalyStatus.ASSESSED, level);
    }

    private static LoginAnomalyAssessment row(LoginAnomalyStatus status, LoginRiskLevel level) {
        return LoginAnomalyAssessment.builder()
                .status(status)
                .riskLevel(level)
                .actorId(1L)
                .activityLogId(1L)
                .occurredAt(Instant.parse("2026-10-19T12:00:00Z"))
                .reasonCodes("X")
                .build();
    }
}
