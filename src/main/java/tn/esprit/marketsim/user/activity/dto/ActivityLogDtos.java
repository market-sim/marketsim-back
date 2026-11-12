package tn.esprit.marketsim.user.activity.dto;

import java.time.Instant;
import java.util.List;

import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.activity.TraceDetails.FieldChange;

public final class ActivityLogDtos {

    private ActivityLogDtos() {
    }

    public record ActivityLogResponse(
            Long id,
            Instant occurredAt,
            Long actorId,
            Long targetUserId,
            ActivityAction action,
            ActivityOutcome outcome,
            String reasonCode,
            String correlationId) {
    }

    public record ActivityLogPageResponse(
            List<ActivityLogResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record ActivityLogDetailResponse(
            Long id,
            Instant occurredAt,
            Long actorId,
            Long targetUserId,
            ActivityAction action,
            ActivityOutcome outcome,
            String reasonCode,
            String correlationId,
            boolean detailsAvailable,
            boolean passwordChanged,
            List<FieldChange> changes) {
    }
}
