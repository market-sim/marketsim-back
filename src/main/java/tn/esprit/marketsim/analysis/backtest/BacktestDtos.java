package tn.esprit.marketsim.analysis.backtest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Trade;
import tn.esprit.marketsim.analysis.backtest.PerformanceMetrics.Metrics;

/** Module 3 : objets echanges avec le front pour les backtests. Tous les taux sont des fractions (0.05 = 5 %). */
public final class BacktestDtos {

    private BacktestDtos() {
    }

    /**
     * Demande de backtest. Seuls {@code strategyId} et {@code symbol} sont obligatoires.
     *
     * @param from             debut de la periode (defaut : debut de l'historique)
     * @param to               fin de la periode (defaut : fin de l'historique)
     * @param initialCapital   defaut 10 000
     * @param feeRate          frais par ordre, defaut 0.001 (0,1 %)
     * @param slippage         glissement de prix, defaut 0
     * @param stopLoss         ex. 0.05 pour 5 % (defaut : aucun)
     * @param takeProfit       ex. 0.10 pour 10 % (defaut : aucun)
     * @param positionFraction part du capital engagee par position, defaut 1
     * @param splitDate        date de coupure entrainement / test (defaut : aucune)
     * @param riskFreeRate     taux sans risque annuel pour Sharpe et Sortino, ex. 0.02 pour 2 % (defaut : 0)
     */
    public record BacktestRequest(Long strategyId, String symbol, LocalDate from, LocalDate to,
                                  Double initialCapital, Double feeRate, Double slippage, Double stopLoss,
                                  Double takeProfit, Double positionFraction, LocalDate splitDate,
                                  Double riskFreeRate) {

        /** Demande sans taux sans risque (valeur par defaut). */
        public BacktestRequest(Long strategyId, String symbol, LocalDate from, LocalDate to, Double initialCapital,
                               Double feeRate, Double slippage, Double stopLoss, Double takeProfit,
                               Double positionFraction, LocalDate splitDate) {
            this(strategyId, symbol, from, to, initialCapital, feeRate, slippage, stopLoss, takeProfit,
                    positionFraction, splitDate, null);
        }
    }

    /** Demande de comparaison : les reglages de simulation sont communs a toutes les strategies. */
    public record CompareRequest(List<Long> strategyIds, String symbol, LocalDate from, LocalDate to,
                                 Double initialCapital, Double feeRate, Double slippage, Double stopLoss,
                                 Double takeProfit, Double positionFraction, LocalDate splitDate,
                                 Double riskFreeRate) {

        /** Demande sans taux sans risque (valeur par defaut). */
        public CompareRequest(List<Long> strategyIds, String symbol, LocalDate from, LocalDate to,
                              Double initialCapital, Double feeRate, Double slippage, Double stopLoss,
                              Double takeProfit, Double positionFraction, LocalDate splitDate) {
            this(strategyIds, symbol, from, to, initialCapital, feeRate, slippage, stopLoss, takeProfit,
                    positionFraction, splitDate, null);
        }
    }

    /** Courbes journalieres : les quatre tableaux ont la meme taille. */
    public record Curves(LocalDate[] dates, double[] equity, double[] buyHold, double[] drawdown) {
    }

    /**
     * Courbes propres a une strategie dans une comparaison. Un point par seance, aux dates de
     * {@link ComparisonResult#dates()} (communes a toutes les strategies, donc envoyees une seule fois).
     */
    public record ComparisonCurve(double[] equity, double[] drawdown) {
    }

    /** Resultat d'une strategie, avec son etat au moment de la comparaison. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StrategyComparison(Long strategyId, String strategyName, StrategyType strategyType,
                                    StrategyParams params, Metrics metrics, Segment train, Segment test,
                                    ComparisonCurve curves) {
    }

    /**
     * Resultat complet d'une comparaison (enregistre tel quel par l'historique des comparaisons).
     *
     * @param periodsPerYear seances par an utilisees pour annualiser (absent des comparaisons enregistrees avant
     *                       le 2026-11-19, calculees avec 252)
     * @param periodHash     empreinte des seances de la periode comparee seulement ({@code Prices.periodHash}),
     *                       absente des anciens enregistrements
     */
    public record ComparisonResult(String symbol, LocalDate from, LocalDate to, LocalDate splitDate,
                                   String dataSource, String dataHash, BacktestSettings settings,
                                   LocalDate[] dates, double[] buyHold, List<StrategyComparison> strategies,
                                   Integer periodsPerYear, String periodHash) {
    }

    /** Metriques d'une sous-periode (entrainement ou test), simulee a part depuis le capital initial. */
    public record Segment(LocalDate from, LocalDate to, Metrics metrics) {
    }

    /**
     * Resultat complet d'un backtest (enregistre en JSON dans {@link Backtest}).
     *
     * @param periodsPerYear seances par an utilisees pour annualiser (absent des anciens backtests : 252)
     */
    public record BacktestResult(Metrics metrics, Segment train, Segment test, Curves curves, List<Trade> trades,
                                 Integer periodsPerYear) {
    }

    /** Resultat de la verification des cours d'un enregistrement (voir {@link DataCheck}). */
    public record DataCheckResult(DataCheck.Status status, LocalDate from, LocalDate to, LocalDate currentFirst,
                                  LocalDate currentLast) {
    }

    /** Parametres de la strategie et reglages au moment du lancement (enregistres en JSON). */
    public record StoredSettings(StrategyParams params, BacktestSettings settings) {
    }

    /**
     * Backtest complet renvoye au front. {@code train} et {@code test} sont absents sans date de coupure.
     *
     * @param params     parametres de la strategie au moment du lancement (elle a pu etre modifiee depuis)
     * @param dataSource origine de l'historique (YAHOO ou CSV)
     * @param dataHash   empreinte SHA-256 de l'historique utilise (reproductibilite)
     * @param periodsPerYear seances par an utilisees pour annualiser (absent des anciens backtests : 252)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BacktestDetail(Long id, Long strategyId, String strategyName, StrategyType strategyType,
                                 StrategyParams params, String symbol, LocalDate from, LocalDate to,
                                 LocalDate splitDate, LocalDateTime createdAt, String dataSource, String dataHash,
                                 BacktestSettings settings, Metrics metrics, Segment train, Segment test,
                                 Curves curves, List<Trade> trades, Integer periodsPerYear) {
    }
}
