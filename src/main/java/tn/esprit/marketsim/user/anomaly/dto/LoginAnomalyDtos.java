package tn.esprit.marketsim.user.anomaly.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import tn.esprit.marketsim.user.anomaly.LoginAnomalyStatus;
import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;

public final class LoginAnomalyDtos {

    private LoginAnomalyDtos() {
    }

    public record LoginAnomalyResponse(
            Long id,
            Long activityLogId,
            Long actorId,
            Instant occurredAt,
            LoginAnomalyStatus status,
            Double riskScore,
            LoginRiskLevel riskLevel,
            List<String> reasonCodes,
            String modelVersion) {
    }

    public record LoginAnomalyDetailResponse(
            Long id,
            Long activityLogId,
            Long actorId,
            Instant occurredAt,
            LoginAnomalyStatus status,
            Double riskScore,
            LoginRiskLevel riskLevel,
            List<String> reasonCodes,
            String modelVersion,
            Map<String, Object> featureSummary) {
    }

    public record LoginAnomalyPageResponse(
            List<LoginAnomalyResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
