package tn.esprit.marketsim.analysis.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Run;
import tn.esprit.marketsim.analysis.backtest.PerformanceMetrics.Metrics;

/** Seances par an estimees sur les dates reelles, et taux sans risque dans le Sharpe et le Sortino. */
class AnnualizationTest {

    /** Rendements +10 %, -10 %, +10 % : moyenne 1/30, ecart-type (echantillon) 0,11547. */
    private static final double[] EQUITY = {100, 110, 99, 108.9};

    // ===================== Seances par an =====================

    /** Une cryptomonnaie cote tous les jours : 365 points par an. */
    @Test
    void aSeriesQuotedEveryDayHas365PeriodsPerYear() {
        LocalDate start = LocalDate.of(2021, 1, 1);
        LocalDate[] dates = IntStream.range(0, 3 * 365 + 1).mapToObj(start::plusDays).toArray(LocalDate[]::new);
        assertEquals(365, PerformanceMetrics.periodsPerYear(dates));
    }

    /**
     * Une action : jours ouvres, moins neuf jours feries par an (ici les premiers jours ouvres de janvier a
     * septembre). 2020 a 2023 : 1 043 jours ouvres - 36 = 1 007 seances sur 4 ans, soit 252 par an.
     */
    @Test
    void aStockSeriesHas252PeriodsPerYear() {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2020, 1, 1); d.getYear() <= 2023; d = d.plusDays(1)) {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!weekend && !(d.getMonthValue() <= 9 && firstWorkingDayOfMonth(d))) dates.add(d);
        }
        assertEquals(1007, dates.size());
        assertEquals(252, PerformanceMetrics.periodsPerYear(dates.toArray(LocalDate[]::new)));
    }

    /** Jours ouvres sans aucun ferie : 261 par an (la valeur est mesuree, pas supposee). */
    @Test
    void weekdaysWithoutHolidaysGive261() {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2021, 1, 4); d.isBefore(LocalDate.of(2024, 1, 1)); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) dates.add(d);
        }
        assertEquals(261, PerformanceMetrics.periodsPerYear(dates.toArray(LocalDate[]::new)));
    }

    @Test
    void aTooShortHistoryUses252() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        assertEquals(252, PerformanceMetrics.periodsPerYear(
                IntStream.range(0, 60).mapToObj(start::plusDays).toArray(LocalDate[]::new)), "59 jours");
        assertEquals(252, PerformanceMetrics.periodsPerYear(new LocalDate[] {start}));
        assertEquals(252, PerformanceMetrics.periodsPerYear(new LocalDate[0]));
        assertEquals(365, PerformanceMetrics.periodsPerYear(
                IntStream.range(0, 91).mapToObj(start::plusDays).toArray(LocalDate[]::new)), "90 jours : estimable");
    }

    @Test
    void volatilityUsesThePeriodsPerYear() {
        double perPeriod = Stats.std(PerformanceMetrics.dailyReturns(EQUITY));
        assertEquals(perPeriod * Math.sqrt(365), PerformanceMetrics.volatility(EQUITY, 365), 1e-12);
        assertEquals(perPeriod * Math.sqrt(252), PerformanceMetrics.volatility(EQUITY, 252), 1e-12);
        assertEquals(PerformanceMetrics.volatility(EQUITY, 252), PerformanceMetrics.volatility(EQUITY), 0.0);
    }

    // ===================== Taux sans risque =====================

    @Test
    void theRiskFreeRateIsCompoundedPerPeriod() {
        assertEquals(0.01, PerformanceMetrics.riskFreePerPeriod(Math.pow(1.01, 4) - 1, 4), 1e-15);
        assertEquals(0.0000785849, PerformanceMetrics.riskFreePerPeriod(0.02, 252), 1e-10, "2 % par an sur 252 seances");
        assertEquals(0.0, PerformanceMetrics.riskFreePerPeriod(0, 252), 0.0);
    }

    /**
     * Exemple calcule a la main. Rendements r = +0,10 ; -0,10 ; +0,10. 4 periodes par an, taux sans risque
     * annuel 1,01^4 - 1 = 4,0604 %, soit exactement 1 % par periode.
     * <ul>
     *   <li>moyenne de r = 0,033333 ; ecart-type (n-1) = racine((0,0667^2 + 0,1333^2 + 0,0667^2) / 2) = 0,115470 ;</li>
     *   <li>Sharpe = (0,033333 - 0,01) / 0,115470 x racine(4) = 0,40415 (0,57735 sans taux sans risque) ;</li>
     *   <li>Sortino : r - 0,01 = 0,09 ; -0,11 ; 0,09, ecart a la baisse = racine(0,11^2 / 3) = 0,063509,
     *       Sortino = 0,023333 / 0,063509 x 2 = 0,73481.</li>
     * </ul>
     */
    @Test
    void sharpeAndSortinoUseTheReturnAboveTheRiskFreeRate() {
        double rate = Math.pow(1.01, 4) - 1;
        assertEquals(0.40414519, PerformanceMetrics.sharpe(EQUITY, 4, rate), 1e-8);
        assertEquals(0.73480943, PerformanceMetrics.sortino(EQUITY, 4, rate), 1e-8);
        assertEquals(0.57735027, PerformanceMetrics.sharpe(EQUITY, 4, 0), 1e-8);
    }

    @Test
    void withoutRiskFreeRateAnd252PeriodsTheSharpeIsTheModule6One() {
        assertEquals(Stats.sharpe(EQUITY), PerformanceMetrics.sharpe(EQUITY, 252, 0), 0.0);
        assertEquals(PerformanceMetrics.sortino(EQUITY), PerformanceMetrics.sortino(EQUITY, 252, 0), 0.0);
        double[] flat = {100, 100, 100, 100};
        assertEquals(0, PerformanceMetrics.sharpe(flat, 252, 0.02), 0.0, "sans variation : 0");
        assertEquals(0, PerformanceMetrics.sharpe(new double[] {100, 101}, 252, 0), 0.0, "trop court : 0");
    }

    @Test
    void aSortinoNeverBelowTheRiskFreeRateIsNotComputable() {
        assertNull(PerformanceMetrics.sortino(new double[] {100, 103, 106.09, 109.27}, 252, 0.02));
        // au-dessus de 0 mais sous le taux sans risque : le Sortino devient calculable (et negatif)
        Double sortino = PerformanceMetrics.sortino(new double[] {100, 100.001, 100.002, 100.003}, 4, 0.2);
        assertTrue(sortino != null && sortino < 0, String.valueOf(sortino));
    }

    @Test
    void computeUsesBothSettings() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate[] dates = IntStream.range(0, 4).mapToObj(start::plusDays).toArray(LocalDate[]::new);
        Run run = new Run(dates, EQUITY, EQUITY, List.of());
        double rate = Math.pow(1.01, 4) - 1;

        Metrics m = PerformanceMetrics.compute(run, 4, rate);

        assertEquals(PerformanceMetrics.sharpe(EQUITY, 4, rate), m.sharpe(), 0.0);
        assertEquals(PerformanceMetrics.sortino(EQUITY, 4, rate), m.sortino(), 0.0);
        assertEquals(PerformanceMetrics.volatility(EQUITY, 4), m.volatility(), 0.0);
        // le rendement annualise (CAGR) ne depend que des dates calendaires
        assertEquals(PerformanceMetrics.compute(run).annualizedReturn(), m.annualizedReturn());
    }

    private static boolean firstWorkingDayOfMonth(LocalDate d) {
        for (LocalDate x = d.withDayOfMonth(1); ; x = x.plusDays(1)) {
            if (x.getDayOfWeek().getValue() <= 5) return x.equals(d);
        }
    }
}
