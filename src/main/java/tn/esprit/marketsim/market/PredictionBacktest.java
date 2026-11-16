package tn.esprit.marketsim.market;

import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Résultat agrégé de backtesting / calibration des prédictions résolues.
 */
@Data
@NoArgsConstructor
public class PredictionBacktest {

    private int total;
    private int resolved;
    private int pending;

    /** Taux de réussite global (correct / résolues). null si aucune résolue. */
    private Double successRate;

    /** Brier score moyen (plus bas = mieux). null si aucune résolue. */
    private Double brierScore;

    /** Statistiques par direction prédite. */
    private Map<String, DirectionStat> byDirection;

    /** Bins de calibration : confiance prédite vs taux de réussite réel. */
    private List<CalibrationBin> calibration;
    /** Predictions reelles deja resolues (horizon ecoule). */
    private int liveResolved;
    /** Predictions rejouees sur l'historique reel (walk-forward, sans donnee du futur). */
    private int historicalCount;
    private Integer historicalHorizon;
    private java.time.LocalDate historicalFrom;
    private java.time.LocalDate historicalTo;
    private String historicalSource;

    @Data
    @NoArgsConstructor
    public static class DirectionStat {
        private int total;
        private int correct;
        private double rate;
    }

    @Data
    @NoArgsConstructor
    public static class CalibrationBin {
        private double from;
        private double to;
        private int count;
        private double avgConfidence;
        private double actualRate;
    }
}