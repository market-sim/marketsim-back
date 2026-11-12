package tn.esprit.marketsim.user.level;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.level.dto.UserLevelDtos.UserLevelResponse;

@Service
@RequiredArgsConstructor
public class UserLevelService {

    private final UserLevelAssessmentRepository assessmentRepository;
    private final UserLevelFeatureExtractor featureExtractor;
    private final UserRepository userRepository;

    @Transactional
    public UserLevelResponse recomputeOwn(Long userId) {
        return recompute(userId);
    }

    /**
     * Un compte supprime n'a plus de date de creation. On renvoie l'evaluation deja stockee
     * sans la recalculer, meme si des journaux d'activite survivent.
     */
    @Transactional
    public UserLevelResponse recomputeForAdmin(Long userId) {
        if (!userRepository.existsById(userId)) {
            return assessmentRepository.findByUserId(userId)
                    .map(this::toResponse)
                    .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        }
        return recompute(userId);
    }

    private UserLevelResponse recompute(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        UserLevelFeatures features = featureExtractor.extract(
                userId,
                user == null ? null : user.getCreatedAt(),
                Instant.now());

        Integer score = null;
        UserLevel level = null;
        String modelVersion = null;
        List<String> reasons;
        boolean insufficient = features.successfulLogins() < UserLevelModel.MIN_SUCCESSFUL_LOGINS;
        if (insufficient) {
            reasons = List.of("INSUFFICIENT_ACTIVITY");
            if (user != null && user.getLevel() != UserLevel.DEBUTANT) {
                user.setLevel(UserLevel.DEBUTANT);
                userRepository.save(user);
            }
        } else {
            UserLevelModel model = UserLevelModel.current();
            score = model.score(features);
            level = UserLevelModel.levelForScore(score);
            modelVersion = model.version();
            reasons = model.explain(features);
            if (user != null && user.getLevel() != level) {
                user.setLevel(level);
                userRepository.save(user);
            }
        }

        UserLevelAssessment row = assessmentRepository.findByUserId(userId)
                .orElseGet(() -> UserLevelAssessment.builder().userId(userId).build());
        row.setScore(score);
        row.setLevel(level);
        row.setInsufficientEvidence(insufficient);
        row.setSuccessfulLoginCount(features.successfulLogins());
        row.setReasonCodes(String.join(",", reasons));
        row.setModelVersion(modelVersion);
        row.setComputedAt(Instant.now());
        assessmentRepository.save(row);
        return toResponse(row);
    }

    private UserLevelResponse toResponse(UserLevelAssessment row) {
        return new UserLevelResponse(
                row.getUserId(),
                row.getLevel(),
                row.getScore(),
                row.isInsufficientEvidence(),
                row.getSuccessfulLoginCount(),
                split(row.getReasonCodes()),
                row.getModelVersion(),
                row.getComputedAt());
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}
