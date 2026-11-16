package tn.esprit.marketsim.user.level;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.progression.ProgressionService;
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
    /** Niveau gagne en tradant (portefeuille + competences) : prioritaire des que le joueur a commence. */
    private final ProgressionService progressionService;

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

    /** Regle unique du niveau affiche (renvoyee par GET /api/users/me/level). */
    public static final String DISPLAY_RULE = "Niveau affiché = niveau de trading (jeu de progression) dès que "
            + "l'utilisateur a un profil de trader ; sinon, niveau d'activité calculé par le modèle IA. "
            + "Le score affiché est toujours celui du niveau retenu.";
    public static final String SOURCE_TRADING = "TRADING";
    public static final String SOURCE_ACTIVITY = "ACTIVITY";

    /**
     * Calcule les DEUX niveaux independamment, puis choisit le niveau affiche ({@link #DISPLAY_RULE}) :
     * <ul>
     *   <li>activite : modele IA du module 1 (score 0-100, raisons), si assez de connexions ;</li>
     *   <li>trading : niveau du jeu de progression et score de competence, si un profil de trader existe.</li>
     * </ul>
     * {@code User.level} (difficulte de l'application) suit le niveau affiche, DEBUTANT par defaut.
     */
    private UserLevelResponse recompute(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        UserLevelFeatures features = featureExtractor.extract(
                userId,
                user == null ? null : user.getCreatedAt(),
                Instant.now());

        // 1. Niveau d'activite (modele IA), jamais ecrase
        Integer activityScore = null;
        UserLevel activityLevel = null;
        String modelVersion = null;
        List<String> reasons;
        boolean insufficient = features.successfulLogins() < UserLevelModel.MIN_SUCCESSFUL_LOGINS;
        if (insufficient) {
            reasons = List.of("INSUFFICIENT_ACTIVITY");
        } else {
            UserLevelModel model = UserLevelModel.current();
            activityScore = model.score(features);
            activityLevel = UserLevelModel.levelForScore(activityScore);
            modelVersion = model.version();
            reasons = model.explain(features);
        }

        // 2. Niveau de trading (jeu), calcule a part
        ProgressionService.TradingAssessment trading = progressionService == null ? null
                : progressionService.tradingAssessment(userId).orElse(null);

        // 3. Niveau affiche : une seule regle
        UserLevel level;
        Integer score;
        String source;
        if (trading != null) {
            level = trading.level();
            score = trading.skillScore();
            source = SOURCE_TRADING;
            reasons = new java.util.ArrayList<>(reasons);
            reasons.add(0, "TRADING_PROGRESSION");
        } else {
            level = activityLevel;
            score = activityScore;
            source = SOURCE_ACTIVITY;
        }
        UserLevel applied = level == null ? UserLevel.DEBUTANT : level;
        if (user != null && user.getLevel() != applied) {
            user.setLevel(applied);
            userRepository.save(user);
        }

        UserLevelAssessment row = assessmentRepository.findByUserId(userId)
                .orElseGet(() -> UserLevelAssessment.builder().userId(userId).build());
        row.setScore(score);
        row.setLevel(level);
        row.setInsufficientEvidence(level == null);
        row.setSuccessfulLoginCount(features.successfulLogins());
        row.setReasonCodes(String.join(",", reasons));
        row.setModelVersion(modelVersion);
        row.setComputedAt(Instant.now());
        row.setActivityLevel(activityLevel);
        row.setActivityScore(activityScore);
        row.setTradingLevel(trading == null ? null : trading.level());
        row.setTradingScore(trading == null ? null : trading.skillScore());
        row.setLevelSource(source);
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
                row.getComputedAt(),
                row.getActivityLevel(),
                row.getActivityScore(),
                row.getTradingLevel(),
                row.getTradingScore(),
                row.getLevelSource() == null ? SOURCE_ACTIVITY : row.getLevelSource(),
                DISPLAY_RULE);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}
