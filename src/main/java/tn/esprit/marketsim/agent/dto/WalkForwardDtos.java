package tn.esprit.marketsim.agent.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import tn.esprit.marketsim.agent.AgentStrategy;

/** Donnees de marche reelles et backtest walk-forward. */
public final class WalkForwardDtos {

    private WalkForwardDtos() {
    }

    public record PricePoint(LocalDate date, double close) {
    }

    /** Historique reel d'un actif (points sous-echantillonnes pour le graphique). */
    public record SeriesInfo(String symbol, String source, int points, LocalDate from, LocalDate to,
                             double firstClose, double lastClose, String dataHash, List<PricePoint> prices) {
    }

    public record ImportRequest(@NotBlank String symbol, @NotBlank String csv) {
    }

    /**
     * @param trainDays fenetre d'entrainement (252 a 1260 jours, defaut 504)
     * @param testDays  fenetre de test hors echantillon (21 a 504 jours, defaut 126)
     * @param episodes  passes d'apprentissage par fenetre (agent Q-learning uniquement, defaut 300)
     */
    public record WalkForwardRequest(@NotBlank String symbol, Integer trainDays, Integer testDays, Integer episodes,
                                     Long seed) {
    }

    public record WindowDto(int index, LocalDate trainFrom, LocalDate trainTo, LocalDate testFrom, LocalDate testTo,
                            double agentReturn, double benchmarkReturn, double excess, double inSampleReturn,
                            double inSampleBenchmark, double maxDrawdown, int trades, double exposure) {
    }

    public record CurvePoint(LocalDate date, double agent, double benchmark) {
    }

    public record Performance(double totalReturn, double annualizedReturn, double sharpe, double maxDrawdown) {
    }

    /** Test apparie sur les rendements par fenetre : agent - buy &amp; hold. */
    public record SignificanceTest(int windows, double meanExcess, double ciLow, double ciHigh, double tStat,
                                   double pValue, double pBootstrap, double pSign, double winRate, double cohenD) {
    }

    public record WalkForwardResult(String symbol, String source, String dataHash, LocalDate dataFrom, LocalDate dataTo,
                                    int bars, AgentStrategy strategy, int riskLevel, int trainDays, int testDays,
                                    int episodes, long seed, long elapsedMs, List<WindowDto> windows,
                                    Performance agent, Performance benchmark, SignificanceTest test,
                                    double overfitGap, List<CurvePoint> curve, List<PricePoint> prices, String verdict,
                                    Long auditRecordId) {
        public WalkForwardResult withAuditRecord(Long id) {
            return new WalkForwardResult(symbol, source, dataHash, dataFrom, dataTo, bars, strategy, riskLevel, trainDays,
                    testDays, episodes, seed, elapsedMs, windows, agent, benchmark, test, overfitGap, curve, prices,
                    verdict, id);
        }
    }
}
