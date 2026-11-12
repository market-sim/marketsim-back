package tn.esprit.marketsim.user.score.dto;

import java.time.Instant;

import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;

public final class SecurityScoreDtos {

    private SecurityScoreDtos() {
    }

    public record SecurityScoreResponse(
            Long userId,
            Integer score,
            LoginRiskLevel riskBand,
            int assessmentCount,
            boolean insufficientEvidence,
            Instant computedAt) {
    }
}
