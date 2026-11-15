package tn.esprit.marketsim.market;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Résultat d'évaluation d'une prédiction stockée (métriques calculées au moment de la génération).
 */
@Getter
@AllArgsConstructor
public class PredictionEvaluation {
    private final Long predictionId;
    private final String modelName;
    private final String modelVersion;
    private final Integer evaluationPoints;
    private final Double mae;
    private final Double rmse;
    private final Double mape;
    private final String status; // GENERATED / RESOLVED / UNAVAILABLE ...
}