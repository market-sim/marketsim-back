package tn.esprit.marketsim.user.score;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyAssessment;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyAssessmentRepository;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyStatus;
import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;
import tn.esprit.marketsim.user.score.dto.SecurityScoreDtos.SecurityScoreResponse;

@Service
@RequiredArgsConstructor
public class SecurityScoreService {

    private final UserSecurityScoreRepository scoreRepository;
    private final LoginAnomalyAssessmentRepository assessmentRepository;
    private final UserRepository userRepository;

    @Transactional
    public SecurityScoreResponse recomputeOwn(Long userId) {
        return recompute(userId);
    }

    /**
     * Compte existant : recalcul. Compte supprime : recalcul si des evaluations survivent,
     * sinon renvoie le score deja persiste. Aucun historique → 404.
     */
    @Transactional
    public SecurityScoreResponse recomputeForAdmin(Long userId) {
        if (userRepository.existsById(userId) || assessmentRepository.existsByActorId(userId)) {
            return recompute(userId);
        }
        return scoreRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
    }

    private SecurityScoreResponse recompute(Long userId) {
        List<LoginAnomalyAssessment> qualifying = assessmentRepository.findByActorId(userId).stream()
                .filter(a -> a.getStatus() == LoginAnomalyStatus.ASSESSED && a.getRiskLevel() != null)
                .toList();

        int count = qualifying.size();
        Integer score = null;
        LoginRiskLevel band = null;
        boolean insufficient = count < SecurityScoreCalculator.MIN_QUALIFYING;
        if (!insufficient) {
            int weightSum = qualifying.stream().mapToInt(a -> SecurityScoreCalculator.weight(a.getRiskLevel())).sum();
            score = SecurityScoreCalculator.score(weightSum, count);
            band = SecurityScoreCalculator.band(score);
        }

        UserSecurityScore row = scoreRepository.findByUserId(userId).orElseGet(() -> UserSecurityScore.builder()
                .userId(userId)
                .build());
        row.setScore(score);
        row.setRiskBand(band);
        row.setAssessmentCount(count);
        row.setInsufficientEvidence(insufficient);
        row.setComputedAt(Instant.now());
        scoreRepository.save(row);
        return toResponse(row);
    }

    private SecurityScoreResponse toResponse(UserSecurityScore row) {
        return new SecurityScoreResponse(
                row.getUserId(),
                row.getScore(),
                row.getRiskBand(),
                row.getAssessmentCount(),
                row.isInsufficientEvidence(),
                row.getComputedAt());
    }
}
