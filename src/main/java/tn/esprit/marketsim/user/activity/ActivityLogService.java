package tn.esprit.marketsim.user.activity;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.activity.TraceDetails.FieldChange;
import tn.esprit.marketsim.user.activity.dto.ActivityLogDtos.ActivityLogDetailResponse;
import tn.esprit.marketsim.user.activity.dto.ActivityLogDtos.ActivityLogPageResponse;
import tn.esprit.marketsim.user.activity.dto.ActivityLogDtos.ActivityLogResponse;

@Slf4j
@Service
public class ActivityLogService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final ActivityLogRepository repository;
    private final ActivityLogWriter writer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ActivityLogService(ActivityLogRepository repository, ActivityLogWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    public Long recordImmediate(Long actorId, Long targetUserId, ActivityAction action,
                                ActivityOutcome outcome, String reasonCode) {
        return recordImmediate(actorId, targetUserId, action, outcome, reasonCode, TraceDetails.empty(), null);
    }

    public Long recordImmediate(Long actorId, Long targetUserId, ActivityAction action,
                                ActivityOutcome outcome, String reasonCode, TraceDetails details) {
        return recordImmediate(actorId, targetUserId, action, outcome, reasonCode, details, null);
    }

    public Long recordImmediate(Long actorId, Long targetUserId, ActivityAction action,
                                ActivityOutcome outcome, String reasonCode, TraceDetails details,
                                Instant occurredAt) {
        return writer.write(actorId, targetUserId, action, outcome, reasonCode, details, occurredAt);
    }

    public void recordAfterCommit(Long actorId, Long targetUserId, ActivityAction action, String reasonCode) {
        recordAfterCommit(actorId, targetUserId, action, reasonCode, TraceDetails.empty(), null, null);
    }

    public void recordAfterCommit(Long actorId, Long targetUserId, ActivityAction action,
                                  String reasonCode, TraceDetails details) {
        recordAfterCommit(actorId, targetUserId, action, reasonCode, details, null, null);
    }

    public void recordAfterCommit(Long actorId, Long targetUserId, ActivityAction action,
                                  String reasonCode, TraceDetails details, LongConsumer afterPersist) {
        recordAfterCommit(actorId, targetUserId, action, reasonCode, details, afterPersist, null);
    }

    public void recordAfterCommit(Long actorId, Long targetUserId, ActivityAction action,
                                  String reasonCode, TraceDetails details, LongConsumer afterPersist,
                                  Instant occurredAt) {
        TraceDetails snapshot = details == null ? TraceDetails.empty() : details;
        Instant attemptAt = occurredAt != null ? occurredAt : Instant.now();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Long id = writer.write(actorId, targetUserId, action, ActivityOutcome.SUCCESS,
                            reasonCode, snapshot, attemptAt);
                    if (afterPersist != null) {
                        afterPersist.accept(id);
                    }
                }
            });
        } else {
            Long id = writer.write(actorId, targetUserId, action, ActivityOutcome.SUCCESS,
                    reasonCode, snapshot, attemptAt);
            if (afterPersist != null) {
                afterPersist.accept(id);
            }
        }
    }

    @Transactional(readOnly = true)
    public ActivityLogPageResponse search(
            Long actorId,
            ActivityAction action,
            ActivityOutcome outcome,
            Instant from,
            Instant to,
            int page,
            int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("La date de debut doit etre anterieure a la date de fin (UTC)");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page doit etre >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size doit etre entre 1 et " + MAX_PAGE_SIZE);
        }

        Specification<ActivityLog> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (actorId != null) {
                p = cb.and(p, cb.equal(root.get("actorId"), actorId));
            }
            if (action != null) {
                p = cb.and(p, cb.equal(root.get("action"), action));
            }
            if (outcome != null) {
                p = cb.and(p, cb.equal(root.get("outcome"), outcome));
            }
            if (from != null) {
                p = cb.and(p, cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                p = cb.and(p, cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return p;
        };

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        Page<ActivityLog> result = repository.findAll(spec, pageable);
        List<ActivityLogResponse> content = result.getContent().stream().map(this::toListResponse).toList();
        return new ActivityLogPageResponse(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ActivityLogDetailResponse getById(Long id) {
        ActivityLog logEntry = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Journal introuvable"));
        return toDetailResponse(logEntry);
    }

    private ActivityLogResponse toListResponse(ActivityLog logEntry) {
        return new ActivityLogResponse(
                logEntry.getId(),
                logEntry.getOccurredAt(),
                logEntry.getActorId(),
                logEntry.getTargetUserId(),
                logEntry.getAction(),
                logEntry.getOutcome(),
                logEntry.getReasonCode(),
                logEntry.getCorrelationId());
    }

    private ActivityLogDetailResponse toDetailResponse(ActivityLog logEntry) {
        boolean available = logEntry.isDetailsAvailable();
        List<FieldChange> changes = available ? parseChanges(logEntry.getChangesJson()) : Collections.emptyList();
        return new ActivityLogDetailResponse(
                logEntry.getId(),
                logEntry.getOccurredAt(),
                logEntry.getActorId(),
                logEntry.getTargetUserId(),
                logEntry.getAction(),
                logEntry.getOutcome(),
                logEntry.getReasonCode(),
                logEntry.getCorrelationId(),
                available,
                available && logEntry.isPasswordChanged(),
                changes);
    }

    private List<FieldChange> parseChanges(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<FieldChange>>() {
            });
        } catch (Exception e) {
            log.warn("JSON de changements illisible pour un journal", e);
            return List.of();
        }
    }
}
