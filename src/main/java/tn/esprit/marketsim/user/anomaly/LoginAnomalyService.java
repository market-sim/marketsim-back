package tn.esprit.marketsim.user.anomaly;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLog;
import tn.esprit.marketsim.user.activity.ActivityLogRepository;
import tn.esprit.marketsim.user.anomaly.dto.LoginAnomalyDtos.LoginAnomalyDetailResponse;
import tn.esprit.marketsim.user.anomaly.dto.LoginAnomalyDtos.LoginAnomalyPageResponse;
import tn.esprit.marketsim.user.anomaly.dto.LoginAnomalyDtos.LoginAnomalyResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAnomalyService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final LoginAnomalyAssessmentRepository assessmentRepository;
    private final ActivityLogRepository activityLogRepository;
    private final LoginFeatureExtractor featureExtractor;
    private final LoginRequestContext requestContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assessLogin(Long activityLogId, Long actorId, boolean loginFailed) {
        assessLogin(activityLogId, actorId, loginFailed, requestContext.userAgent(), requestContext.deviceHint(), null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assessLogin(Long activityLogId, Long actorId, boolean loginFailed,
                            String userAgent, String deviceHint) {
        assessLogin(activityLogId, actorId, loginFailed, userAgent, deviceHint, null);
    }

    /**
     * Evalue la tentative a l'instant {@code attemptAt} (temps de la requete),
     * pas a l'instant d'ecriture eventuellement plus tardif du journal.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assessLogin(Long activityLogId, Long actorId, boolean loginFailed,
                            String userAgent, String deviceHint, Instant attemptAt) {
        if (activityLogId == null) {
            return;
        }
        if (assessmentRepository.findByActivityLogId(activityLogId).isPresent()) {
            return;
        }
        // Coupe l'historique strictement avant la tentative — jamais Instant.now() ni un write delaye.
        Instant occurredAt = attemptAt != null ? attemptAt : Instant.now();
        try {
            List<ActivityLog> prior = loadPriorLogins(actorId, occurredAt);
            LoginFeatureVector features = featureExtractor.extract(
                    occurredAt,
                    loginFailed,
                    userAgent,
                    deviceHint,
                    prior);

            if (prior.size() < LoginAnomalyModel.MIN_HISTORY) {
                save(activityLogId, actorId, occurredAt, LoginAnomalyStatus.INSUFFICIENT_HISTORY,
                        null, null, List.of("INSUFFICIENT_HISTORY"), null, features);
                return;
            }

            List<LoginFeatureVector> trainSet = prior.stream()
                    .map(logEntry -> featureExtractor.extract(
                            logEntry.getOccurredAt(),
                            logEntry.getOutcome() != null && logEntry.getOutcome().name().equals("FAILURE"),
                            browserFamilyAsUa(logEntry),
                            null,
                            prior.stream()
                                    .filter(p -> p.getOccurredAt() != null
                                            && p.getOccurredAt().isBefore(logEntry.getOccurredAt()))
                                    .toList()))
                    .toList();

            if (trainSet.size() < LoginAnomalyModel.MIN_HISTORY) {
                save(activityLogId, actorId, occurredAt, LoginAnomalyStatus.INSUFFICIENT_HISTORY,
                        null, null, List.of("INSUFFICIENT_HISTORY"), null, features);
                return;
            }

            LoginAnomalyModel model = LoginAnomalyModel.train(trainSet);
            double score = model.score(features);
            LoginRiskLevel level = LoginRiskLevel.fromScore(score);
            List<String> reasons = model.explain(features, score);
            save(activityLogId, actorId, occurredAt, LoginAnomalyStatus.ASSESSED,
                    score, level, reasons, model.version(), features);
        } catch (Exception ex) {
            log.warn("Evaluation d'anomalie de login indisponible pour activityLogId={}", activityLogId, ex);
            save(activityLogId, actorId, occurredAt, LoginAnomalyStatus.UNAVAILABLE,
                    null, null, List.of("MODEL_UNAVAILABLE"), null, null);
        }
    }

    private String browserFamilyAsUa(ActivityLog log) {
        if (log.getChangesJson() == null || log.getChangesJson().isBlank()) {
            return null;
        }
        try {
            var changes = objectMapper.readValue(log.getChangesJson(),
                    new TypeReference<java.util.List<java.util.Map<String, String>>>() {
                    });
            for (var change : changes) {
                if ("browserFamily".equals(change.get("field")) && change.get("after") != null) {
                    return change.get("after");
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private List<ActivityLog> loadPriorLogins(Long actorId, Instant before) {
        Pageable limit = PageRequest.of(0, 200);
        if (actorId == null) {
            return activityLogRepository.findByActionAndActorIdIsNullAndOccurredAtBeforeOrderByOccurredAtDesc(
                    ActivityAction.LOGIN, before, limit);
        }
        return activityLogRepository.findByActionAndActorIdAndOccurredAtBeforeOrderByOccurredAtDesc(
                ActivityAction.LOGIN, actorId, before, limit);
    }

    private void save(Long activityLogId, Long actorId, Instant occurredAt, LoginAnomalyStatus status,
                      Double score, LoginRiskLevel level, List<String> reasons, String modelVersion,
                      LoginFeatureVector features) {
        LoginAnomalyAssessment assessment = LoginAnomalyAssessment.builder()
                .activityLogId(activityLogId)
                .actorId(actorId)
                .occurredAt(occurredAt)
                .status(status)
                .riskScore(score)
                .riskLevel(level)
                .reasonCodes(String.join(",", reasons))
                .modelVersion(modelVersion)
                .featureSummaryJson(toFeatureSummary(features))
                .build();
        assessmentRepository.save(assessment);
    }

    private String toFeatureSummary(LoginFeatureVector features) {
        if (features == null) {
            return null;
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("browserFamily", features.browserFamily());
        summary.put("recentFailureCount", features.recentFailureCount());
        summary.put("minutesSinceLastLogin", features.minutesSinceLastLogin());
        summary.put("deviceNoveltyBucket", features.deviceNovelty() < 0.3 ? "familiar"
                : features.deviceNovelty() < 0.6 ? "mixed" : "novel");
        summary.put("hourBucket", features.hourSin() > 0.5 ? "morning-afternoon"
                : features.hourSin() < -0.5 ? "evening-night" : "transition");
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public LoginAnomalyPageResponse search(
            Long actorId,
            Long activityLogId,
            LoginRiskLevel riskLevel,
            LoginAnomalyStatus status,
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

        Specification<LoginAnomalyAssessment> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (actorId != null) {
                p = cb.and(p, cb.equal(root.get("actorId"), actorId));
            }
            if (activityLogId != null) {
                p = cb.and(p, cb.equal(root.get("activityLogId"), activityLogId));
            }
            if (riskLevel != null) {
                p = cb.and(p, cb.equal(root.get("riskLevel"), riskLevel));
            }
            if (status != null) {
                p = cb.and(p, cb.equal(root.get("status"), status));
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
        Page<LoginAnomalyAssessment> result = assessmentRepository.findAll(spec, pageable);
        return new LoginAnomalyPageResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public LoginAnomalyDetailResponse getById(Long id) {
        LoginAnomalyAssessment assessment = assessmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Evaluation introuvable"));
        return toDetail(assessment);
    }

    @Transactional(readOnly = true)
    public LoginAnomalyResponse findByActivityLogId(Long activityLogId) {
        return assessmentRepository.findByActivityLogId(activityLogId)
                .map(this::toResponse)
                .orElse(null);
    }

    private LoginAnomalyResponse toResponse(LoginAnomalyAssessment a) {
        return new LoginAnomalyResponse(
                a.getId(),
                a.getActivityLogId(),
                a.getActorId(),
                a.getOccurredAt(),
                a.getStatus(),
                a.getRiskScore(),
                a.getRiskLevel(),
                splitReasons(a.getReasonCodes()),
                a.getModelVersion());
    }

    private LoginAnomalyDetailResponse toDetail(LoginAnomalyAssessment a) {
        Map<String, Object> summary = Map.of();
        if (a.getFeatureSummaryJson() != null) {
            try {
                summary = objectMapper.readValue(a.getFeatureSummaryJson(), new TypeReference<>() {
                });
            } catch (Exception ignored) {
                summary = Map.of();
            }
        }
        return new LoginAnomalyDetailResponse(
                a.getId(),
                a.getActivityLogId(),
                a.getActorId(),
                a.getOccurredAt(),
                a.getStatus(),
                a.getRiskScore(),
                a.getRiskLevel(),
                splitReasons(a.getReasonCodes()),
                a.getModelVersion(),
                summary);
    }

    private static List<String> splitReasons(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
