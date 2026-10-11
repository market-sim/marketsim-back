package tn.esprit.marketsim.agent.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.engine.PriceModel;

/** Requetes / reponses du laboratoire de l'agent (decisions XAI, Monte Carlo, entrainement RL). */
public final class AgentLabDtos {

    private AgentLabDtos() {
    }

    // ===================== Decisions & XAI =====================

    public record ContributionDto(String feature, String label, double value, double contribution) {
    }

    public record DecisionDto(Long id, String symbol, AgentAction action, double quantity, double price,
                              double confidence, double score, Double stopLoss, Double takeProfit,
                              String explanation, List<ContributionDto> contributions, double[] qValues,
                              Long orderId, Integer userRating, String userComment, LocalDateTime createdAt) {
    }

    public record RatingRequest(@NotNull @Min(1) @Max(5) Integer rating, String comment) {
    }

    // ===================== Monte Carlo =====================

    /**
     * @param paths      nombre de trajectoires simulees (100 a 5000)
     * @param seed       graine aleatoire (meme seed = meme resultat) ; null = aleatoire
     * @param antithetic variables antithetiques (reduction de variance)
     * @param mu         drift annuel force (sinon calibration selon le type d'actif)
     * @param sigma      volatilite annuelle forcee
     */
    public record MonteCarloRequest(@NotBlank String symbol, Integer paths, Integer horizonDays, PriceModel model,
                                    Long seed, Boolean antithetic, Double mu, Double sigma, Double capital) {
    }

    /** Statistiques de la distribution des resultats (rendements en fraction : 0.05 = +5 %). */
    public record RiskSummary(double meanReturn, double medianReturn, double stdReturn, double var95,
                              double cvar95, double var99, double probProfit, double meanMaxDrawdown,
                              double worstMaxDrawdown, double meanSharpe) {
    }

    /** Cone de projection : percentiles de la valeur du portefeuille (base 100) a chaque jour. */
    public record FanPoint(int day, double p5, double p25, double p50, double p75, double p95,
                           double benchmarkP50) {
    }

    public record HistogramBin(double from, double to, int agent, int benchmark) {
    }

    /** Convergence de l'estimateur : moyenne cumulee et intervalle de confiance a 95 %. */
    public record ConvergencePoint(int paths, double mean, double lower, double upper) {
    }

    public record MonteCarloResult(Long runId, String symbol, PriceModel model, int paths, int horizonDays,
                                   long seed, boolean antithetic, double mu, double sigma, double startPrice,
                                   long elapsedMs, RiskSummary agent, RiskSummary benchmark,
                                   double probBeatBenchmark, double avgTrades, double winRate, double avgExposure,
                                   List<FanPoint> fan, List<HistogramBin> histogram,
                                   List<ConvergencePoint> convergence, List<double[]> samplePaths) {
    }

    // ===================== Entrainement Q-learning =====================

    public record TrainingRequest(@NotBlank String symbol, Integer episodes, PriceModel model, Long seed) {
    }

    public record LearningPoint(int episode, double avgReward, double avgReturn, double benchmarkReturn,
                                double epsilon) {
    }

    public record PolicyState(int state, String description, int trend, int rsi, int volatility, int position,
                              AgentAction best, double[] q, int visits) {
    }

    /** Evaluation hors echantillon de la politique apprise sur de nouvelles trajectoires. */
    public record Evaluation(int paths, double meanReturn, double benchmarkMeanReturn, double probBeatBenchmark,
                             double meanSharpe, double benchmarkMeanSharpe, double meanMaxDrawdown,
                             double benchmarkMeanMaxDrawdown) {
    }

    public record TrainingResult(String symbol, PriceModel model, int episodes, long seed, long elapsedMs,
                                 List<LearningPoint> curve, List<PolicyState> policy, Evaluation evaluation) {
    }
}
