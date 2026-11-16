package tn.esprit.marketsim.user.level.dto;

import java.time.Instant;
import java.util.List;

import tn.esprit.marketsim.user.UserLevel;

public final class UserLevelDtos {

    private UserLevelDtos() {
    }

    public record UserLevelResponse(
            Long userId,
            UserLevel level,
            Integer score,
            boolean insufficientEvidence,
            int successfulLoginCount,
            List<String> reasonCodes,
            String modelVersion,
            Instant computedAt,
            /** Niveau d'activite du modele IA (module 1) et son score ; null si activite insuffisante. */
            UserLevel activityLevel,
            Integer activityScore,
            /** Niveau du jeu de progression et son score de competence ; null sans profil de trader. */
            UserLevel tradingLevel,
            Integer tradingScore,
            /** TRADING ou ACTIVITY : d'ou viennent level et score. */
            String levelSource,
            /** Regle qui choisit le niveau affiche, en francais. */
            String displayRule) {
    }
}
