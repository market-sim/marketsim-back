package tn.esprit.marketsim.user.activity;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityLogWriter {

    private final ActivityLogRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long write(Long actorId, Long targetUserId, ActivityAction action,
                      ActivityOutcome outcome, String reasonCode, TraceDetails details) {
        return write(actorId, targetUserId, action, outcome, reasonCode, details, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long write(Long actorId, Long targetUserId, ActivityAction action,
                      ActivityOutcome outcome, String reasonCode, TraceDetails details, Instant occurredAt) {
        TraceDetails safe = details == null ? TraceDetails.empty() : details;
        Instant at = occurredAt != null ? occurredAt : Instant.now();
        ActivityLog saved = repository.save(ActivityLog.builder()
                .occurredAt(at)
                .actorId(actorId)
                .targetUserId(targetUserId)
                .action(action)
                .outcome(outcome)
                .reasonCode(reasonCode == null ? "UNSPECIFIED" : reasonCode)
                .correlationId(OperationContext.getCorrelationId())
                .changesJson(toJson(safe))
                .passwordChanged(safe.passwordChanged())
                .detailsAvailable(true)
                .build());
        return saved.getId();
    }

    private String toJson(TraceDetails details) {
        try {
            return objectMapper.writeValueAsString(details.changes());
        } catch (JsonProcessingException e) {
            log.warn("Impossible de serialiser les changements d'activite", e);
            return "[]";
        }
    }
}
