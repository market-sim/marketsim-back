package tn.esprit.marketsim.user.score;

import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;

/**
 * Score utilisateur 0–100 uniquement a partir des niveaux d'evaluations de connexion deja calculees.
 * Poids : HIGH=3, MEDIUM=2, LOW=1. Normalise par le poids maximum (3).
 */
public final class SecurityScoreCalculator {

    public static final int MIN_QUALIFYING = 3;

    private SecurityScoreCalculator() {
    }

    public static int weight(LoginRiskLevel level) {
        return switch (level) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }

    /** Arrondi deterministe. Meme somme et meme effectif donnent toujours le meme score. */
    public static int score(int weightSum, int qualifyingCount) {
        if (qualifyingCount < MIN_QUALIFYING) {
            throw new IllegalArgumentException("Preuves insuffisantes");
        }
        double raw = 100.0 * weightSum / (3.0 * qualifyingCount);
        long rounded = Math.round(raw);
        if (rounded < 0) {
            return 0;
        }
        if (rounded > 100) {
            return 100;
        }
        return (int) rounded;
    }

    public static LoginRiskLevel band(int score) {
        if (score < 40) {
            return LoginRiskLevel.LOW;
        }
        if (score < 70) {
            return LoginRiskLevel.MEDIUM;
        }
        return LoginRiskLevel.HIGH;
    }
}
