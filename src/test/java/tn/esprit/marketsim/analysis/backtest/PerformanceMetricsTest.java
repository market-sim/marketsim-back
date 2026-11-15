package tn.esprit.marketsim.analysis.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.ExitReason;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Run;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Trade;
import tn.esprit.marketsim.analysis.backtest.PerformanceMetrics.DrawdownEpisode;
import tn.esprit.marketsim.analysis.backtest.PerformanceMetrics.Metrics;

/** Metriques de performance : chacune est verifiee sur un exemple calcule a la main. */
class PerformanceMetricsTest {

    private static final double EPS = 1e-9;
    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    private static final double SQRT_252 = Math.sqrt(252);

    /** Courbe de reference : +10 %, -10 %, +10 %. */
    private static final double[] EQUITY = {100, 110, 99, 108.9};

    private static LocalDate[] dates(int n) {
        LocalDate[] d = new LocalDate[n];
        for (int i = 0; i < n; i++) d[i] = START.plusDays(i);
        return d;
    }

    /** Trade dont seuls le resultat, le rendement et la duree comptent pour les statistiques. */
    private static Trade trade(double pnl, double returnRate, int bars, int holdingDays) {
        return new Trade(START, 100, START.plusDays(holdingDays), 100, 10, 1000, pnl, returnRate, 0,
                bars, holdingDays, ExitReason.SIGNAL);
    }

    /** Deux gagnants (+100, +50) et un perdant (-60). */
    private static final List<Trade> TRADES = List.of(
            trade(100, 0.10, 7, 10), trade(50, 0.05, 3, 4), trade(-60, -0.06, 5, 6));

    // ===================== Rendement =====================

    @Test
    void dailyAndTotalReturns() {
        assertArrayEquals(new double[] {0.10, -0.10, 0.10}, PerformanceMetrics.dailyReturns(EQUITY), EPS);
        assertEquals(0.089, PerformanceMetrics.totalReturn(EQUITY), EPS);
    }

    @Test
    void annualizedReturnUsesCalendarDays() {
        // 2020-01-01 -> 2024-01-01 = 1461 jours = exactement 4 x 365,25 : +46,41 % en 4 ans = +10 % par an
        assertEquals(0.10, PerformanceMetrics.annualizedReturn(0.4641,
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1)), EPS);
        // sur une demi-annee (183 jours ~ 0,501 an), +10 % donne environ +21 % annualise
        assertEquals(Math.pow(1.10, 365.25 / 183) - 1, PerformanceMetrics.annualizedReturn(0.10,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 7, 2)), EPS);
        assertEquals(-1.0, PerformanceMetrics.annualizedReturn(-1.0,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 7, 2)), "capital entierement perdu");
        assertNull(PerformanceMetrics.annualizedReturn(0.10, START, START), "duree nulle");
    }

    // ===================== Risque =====================

    @Test
    void volatilityIsTheAnnualizedStandardDeviationOfDailyReturns() {
        // moyenne = 0,0333 ; ecarts = +0,0667, -0,1333, +0,0667 ; somme des carres = 0,026667
        // ecart-type (n-1) = racine(0,026667 / 2) = 0,115470
        assertEquals(0.115470053838 * SQRT_252, PerformanceMetrics.volatility(EQUITY), 1e-9);
    }

    @Test
    void sharpeComesFromTheSharedStatsClass() {
        // moyenne / ecart-type = 0,033333 / 0,115470 = 0,288675 ; x racine(252) = 4,582576
        assertEquals(4.582575695, Stats.sharpe(EQUITY), 1e-8);
        Run run = new Run(dates(4), EQUITY, EQUITY, List.of());
        assertEquals(Stats.sharpe(EQUITY), PerformanceMetrics.compute(run).sharpe(), 0.0);
    }

    @Test
    void sortinoOnlyPenalizesNegativeReturns() {
        // un seul rendement negatif (-10 %) : ecart a la baisse = racine(0,01 / 3) = 0,057735
        // 0,033333 / 0,057735 = 0,577350 ; x racine(252) = 9,165151
        assertEquals(0.1 / 3 / Math.sqrt(0.01 / 3) * SQRT_252, PerformanceMetrics.sortino(EQUITY), EPS);
        assertEquals(9.165151390, PerformanceMetrics.sortino(EQUITY), 1e-8);
        assertNull(PerformanceMetrics.sortino(new double[] {100, 101, 102, 103}), "aucune baisse : non calculable");
    }

    @Test
    void calmarIsAnnualizedReturnOverMaxDrawdown() {
        assertEquals(2.0, PerformanceMetrics.calmar(0.20, -0.10), EPS);
        assertEquals(-0.5, PerformanceMetrics.calmar(-0.10, -0.20), EPS);
        assertNull(PerformanceMetrics.calmar(0.20, 0), "aucun drawdown : non calculable");
        assertNull(PerformanceMetrics.calmar(null, -0.10));
    }

    @Test
    void drawdownCurveIsTheDistanceToTheRunningPeak() {
        assertArrayEquals(new double[] {0, 0, -0.10, -0.01}, PerformanceMetrics.drawdownCurve(EQUITY), EPS);
    }

    @Test
    void maxDrawdownEpisodeWithRecovery() {
        // sommet 120 (jour 1), creux 90 (jour 2) = -25 %, sommet retrouve a 125 (jour 4)
        // le second drawdown (125 -> 110 = -12 %) est moins profond
        double[] equity = {100, 120, 90, 100, 125, 110};
        DrawdownEpisode dd = PerformanceMetrics.maxDrawdownEpisode(equity);
        assertEquals(-0.25, dd.depth(), EPS);
        assertEquals(Stats.maxDrawdown(equity), dd.depth(), 0.0, "meme valeur que le module 6");
        assertEquals(1, dd.peakIndex());
        assertEquals(2, dd.troughIndex());
        assertEquals(4, dd.recoveryIndex());

        Metrics m = PerformanceMetrics.compute(new Run(dates(6), equity, equity, List.of()));
        assertEquals(3, m.maxDrawdownDays(), "du jour 1 au jour 4");
        assertEquals(START.plusDays(1), m.maxDrawdownPeak());
        assertEquals(START.plusDays(2), m.maxDrawdownTrough());
        assertEquals(START.plusDays(4), m.maxDrawdownRecovery());
    }

    @Test
    void maxDrawdownStartsOnTheLastDayAtThePeak() {
        // le portefeuille reste a 120 deux jours : le drawdown commence le second (jour 2)
        DrawdownEpisode dd = PerformanceMetrics.maxDrawdownEpisode(new double[] {100, 120, 120, 90, 130});
        assertEquals(2, dd.peakIndex());
        assertEquals(3, dd.troughIndex());
        assertEquals(4, dd.recoveryIndex());
    }

    @Test
    void maxDrawdownWithoutRecoveryLastsUntilTheEnd() {
        // sommet 110 (jour 1), creux 99 (jour 2), jamais retrouve : duree comptee jusqu'au dernier jour
        Metrics m = PerformanceMetrics.compute(new Run(dates(4), EQUITY, EQUITY, List.of()));
        assertEquals(-0.10, m.maxDrawdown(), EPS);
        assertEquals(2, m.maxDrawdownDays(), "du jour 1 au jour 3 (fin)");
        assertNull(m.maxDrawdownRecovery());
    }

    @Test
    void aCurveThatOnlyRisesHasNoDrawdown() {
        double[] equity = {100, 101, 102, 103};
        Metrics m = PerformanceMetrics.compute(new Run(dates(4), equity, equity, List.of()));
        assertEquals(0, m.maxDrawdown(), 0.0);
        assertEquals(0, m.maxDrawdownDays());
        assertNull(m.maxDrawdownPeak());
        assertNull(m.maxDrawdownTrough());
        assertNull(m.calmar());
    }

    // ===================== Trades =====================

    @Test
    void tradeStatistics() {
        assertEquals(2.0 / 3, PerformanceMetrics.winRate(TRADES), EPS);
        assertEquals(2.5, PerformanceMetrics.profitFactor(TRADES), EPS, "(100 + 50) / 60");
        assertEquals(75, PerformanceMetrics.averagePnl(TRADES, true), EPS, "(100 + 50) / 2");
        assertEquals(-60, PerformanceMetrics.averagePnl(TRADES, false), EPS);
        assertEquals(0.075, PerformanceMetrics.averageReturn(TRADES, true), EPS);
        assertEquals(-0.06, PerformanceMetrics.averageReturn(TRADES, false), EPS);
        assertEquals(20.0 / 3, PerformanceMetrics.averageHoldingDays(TRADES), EPS, "(10 + 4 + 6) / 3");
    }

    @Test
    void exposureIsTheShareOfSessionsSpentInvested() {
        // 31 points = 30 intervalles ; trades de 7 + 3 + 5 = 15 seances -> 50 %
        assertEquals(0.5, PerformanceMetrics.exposure(TRADES, 31), EPS);
        assertEquals(0, PerformanceMetrics.exposure(List.of(), 31), 0.0);
    }

    @Test
    void withoutTradesTheTradeMetricsAreNotComputable() {
        List<Trade> none = List.of();
        assertNull(PerformanceMetrics.winRate(none));
        assertNull(PerformanceMetrics.profitFactor(none));
        assertNull(PerformanceMetrics.averagePnl(none, true));
        assertNull(PerformanceMetrics.averagePnl(none, false));
        assertNull(PerformanceMetrics.averageHoldingDays(none));
    }

    @Test
    void profitFactorIsNotComputableWithoutLosingTrade() {
        List<Trade> winners = List.of(trade(100, 0.10, 7, 10), trade(50, 0.05, 3, 4));
        assertNull(PerformanceMetrics.profitFactor(winners), "division par zero : infini");
        assertEquals(1.0, PerformanceMetrics.winRate(winners), EPS);
        assertNull(PerformanceMetrics.averagePnl(winners, false));
    }

    @Test
    void aTradeAtZeroIsNeitherAWinNorALoss() {
        List<Trade> trades = List.of(trade(0, 0, 2, 2), trade(40, 0.04, 2, 2));
        assertEquals(0.5, PerformanceMetrics.winRate(trades), EPS);
        assertNull(PerformanceMetrics.profitFactor(trades));
    }

    // ===================== Ensemble =====================

    @Test
    void computeGathersEverythingAndComparesToBuyAndHold() {
        double[] buyHold = {100, 105, 100, 120};   // +20 %
        Metrics m = PerformanceMetrics.compute(new Run(dates(4), EQUITY, buyHold, TRADES));
        assertEquals(108.9, m.finalCapital(), EPS);
        assertEquals(0.089, m.totalReturn(), EPS);
        assertEquals(0.20, m.buyHoldReturn(), EPS);
        assertEquals(0.089 - 0.20, m.excessReturn(), EPS, "ecart au buy and hold");
        assertEquals(3, m.tradeCount());
        assertEquals(2.0 / 3, m.winRate(), EPS);
        assertEquals(Math.pow(1.089, 365.25 / 3) - 1, m.annualizedReturn(), 1e-6, "3 jours calendaires");
        assertEquals(m.annualizedReturn() / 0.10, m.calmar(), 1e-6);
    }

    @Test
    void roundedCopyKeepsNullsAndRoundsRates() {
        double[] equity = {100, 101, 102, 103};
        Metrics m = PerformanceMetrics.compute(new Run(dates(4), equity, equity, List.of())).rounded();
        assertEquals(0.03, m.totalReturn(), 0.0);
        assertNull(m.winRate());
        assertNull(m.sortino());
    }
}
