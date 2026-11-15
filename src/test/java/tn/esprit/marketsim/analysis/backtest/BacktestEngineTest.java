package tn.esprit.marketsim.analysis.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.ExitReason;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Run;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Trade;

/** Moteur de backtesting : chaque regle est verifiee sur une petite serie dont le resultat se calcule a la main. */
class BacktestEngineTest {

    private static final double EPS = 1e-9;
    private static final LocalDate START = LocalDate.of(2024, 1, 1);

    private static LocalDate[] dates(int n) {
        LocalDate[] d = new LocalDate[n];
        for (int i = 0; i < n; i++) d[i] = START.plusDays(i);
        return d;
    }

    /** Signaux HOLD partout, sauf aux indices donnes. */
    private static Signal[] signals(int n, int[] buys, int[] sells) {
        Signal[] s = new Signal[n];
        Arrays.fill(s, Signal.HOLD);
        for (int i : buys) s[i] = Signal.BUY;
        for (int i : sells) s[i] = Signal.SELL;
        return s;
    }

    private static BacktestSettings settings(double fee, double slippage, Double stopLoss, Double takeProfit,
                                             double fraction) {
        return new BacktestSettings(1000, fee, slippage, stopLoss, takeProfit, fraction);
    }

    private static BacktestSettings noCost() {
        return settings(0, 0, null, null, 1);
    }

    private static Run run(double[] closes, Signal[] signals, BacktestSettings s) {
        return BacktestEngine.run(dates(closes.length), closes, signals, 0, closes.length - 1, s);
    }

    // ===================== Execution a J+1 =====================

    @Test
    void aSignalOfDayJIsExecutedAtTheCloseOfDayJPlus1() {
        // indice :            0    1    2    3    4    5
        double[] closes = {100, 100, 110, 120, 130, 125};
        // achat decide a la cloture du jour 1, vente decidee a la cloture du jour 3
        Run r = run(closes, signals(6, new int[] {1}, new int[] {3}), noCost());

        assertEquals(1, r.trades().size());
        Trade t = r.trades().get(0);
        assertEquals(START.plusDays(2), t.entryDate(), "achat execute le jour 2, pas le jour 1");
        assertEquals(110, t.entryPrice(), EPS, "au prix du jour 2 (110), pas a celui du signal (100)");
        assertEquals(START.plusDays(4), t.exitDate(), "vente executee le jour 4, pas le jour 3");
        assertEquals(130, t.exitPrice(), EPS, "au prix du jour 4 (130), pas a celui du signal (120)");
        assertEquals(ExitReason.SIGNAL, t.reason());
        assertEquals(2, t.bars());
        assertEquals(2, t.holdingDays());

        // 1000 / 110 = 9,0909 titres ; valorises 120 puis vendus 130
        double units = 1000.0 / 110;
        assertArrayEquals(new double[] {1000, 1000, 1000, units * 120, units * 130, units * 130}, r.equity(), EPS);
        assertEquals(units * 130 - 1000, t.pnl(), EPS);
        assertEquals(130.0 / 110 - 1, t.returnRate(), EPS);
    }

    @Test
    void theFirstDayOfThePeriodOnlyObserves() {
        double[] closes = {100, 100, 100, 100, 110, 120, 130, 140, 150, 160};
        LocalDate[] d = dates(10);
        // signal d'achat la veille du debut de la periode (jour 2) : il n'est pas execute
        Run before = BacktestEngine.run(d, closes, signals(10, new int[] {2}, new int[0]), 3, 9, noCost());
        assertTrue(before.trades().isEmpty());
        // signal d'achat le premier jour de la periode (jour 3) : execute le jour 4
        Run first = BacktestEngine.run(d, closes, signals(10, new int[] {3}, new int[0]), 3, 9, noCost());
        assertEquals(START.plusDays(4), first.trades().get(0).entryDate());
        assertEquals(7, first.equity().length, "un point par seance de la periode");
        assertEquals(START.plusDays(3), first.dates()[0]);
    }

    /** Biais d'anticipation : changer les prix APRES le jour J ne modifie ni les trades ni le capital jusqu'a J. */
    @Test
    void theEngineNeverUsesFuturePrices() {
        int n = 300;
        int j = 200;
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = 100 + 0.2 * i + 5 * Math.sin(i / 4.0);
        double[] b = a.clone();
        for (int i = j + 1; i < n; i++) b[i] = a[i] * 0.6 + 4 * Math.cos(i);   // futur completement different

        StrategyParams params = new StrategyParams();
        params.setFastPeriod(5);
        params.setSlowPeriod(20);
        BacktestSettings s = settings(0.001, 0.0005, 0.05, 0.10, 1);
        LocalDate[] d = dates(n);
        Run ra = BacktestEngine.run(d, a, Signals.compute(StrategyType.SMA_CROSS, params, a, 0), 0, n - 1, s);
        Run rb = BacktestEngine.run(d, b, Signals.compute(StrategyType.SMA_CROSS, params, b, 0), 0, n - 1, s);

        assertArrayEquals(Arrays.copyOfRange(ra.equity(), 0, j + 1), Arrays.copyOfRange(rb.equity(), 0, j + 1), 0.0);
        List<Trade> pastA = ra.trades().stream().filter(t -> !t.exitDate().isAfter(d[j])).toList();
        List<Trade> pastB = rb.trades().stream().filter(t -> !t.exitDate().isAfter(d[j])).toList();
        assertFalse(pastA.isEmpty(), "le test n'a de sens que s'il y a des trades avant J");
        assertEquals(pastA, pastB);
    }

    // ===================== Frais et glissement =====================

    @Test
    void feesAreChargedOnBothOrders() {
        double[] closes = {100, 100, 110, 120, 130, 125};
        Run r = run(closes, signals(6, new int[] {1}, new int[] {3}), settings(0.001, 0, null, null, 1));
        Trade t = r.trades().get(0);

        // achat : 1000 engages dont 1 de frais -> 999 / 110 titres
        // vente : 999 / 110 * 130 = 1180,6364 brut, 1,1806 de frais -> 1179,4557 net
        double units = 999.0 / 110;
        double gross = units * 130;
        assertEquals(units, t.quantity(), EPS);
        assertEquals(1000, t.invested(), EPS);
        assertEquals(1 + gross * 0.001, t.fees(), EPS);
        assertEquals(gross * 0.999 - 1000, t.pnl(), EPS);
        assertEquals(1179.4557, r.equity()[5], 1e-4);
        assertEquals(999, r.equity()[2], EPS, "le jour de l'achat, le capital baisse des frais d'achat");
    }

    @Test
    void slippageMakesBuysMoreExpensiveAndSalesCheaper() {
        double[] closes = {100, 100, 110, 120, 130, 125};
        Run r = run(closes, signals(6, new int[] {1}, new int[] {3}), settings(0, 0.01, null, null, 1));
        Trade t = r.trades().get(0);
        assertEquals(111.1, t.entryPrice(), EPS, "110 + 1 %");
        assertEquals(128.7, t.exitPrice(), EPS, "130 - 1 %");
        assertEquals(1000 * 128.7 / 111.1, r.equity()[5], EPS);
    }

    @Test
    void positionFractionLimitsTheCapitalAtRisk() {
        double[] closes = {100, 100, 120, 120};
        Run r = run(closes, signals(4, new int[] {0}, new int[0]), settings(0, 0, null, null, 0.5));
        // 500 investis a 100 -> 5 titres ; 500 restent en liquidites
        assertEquals(500, r.trades().get(0).invested(), EPS);
        assertEquals(5, r.trades().get(0).quantity(), EPS);
        assertEquals(500 + 5 * 120, r.equity()[2], EPS);
    }

    // ===================== Stop-loss et take-profit =====================

    @Test
    void stopLossExitsAtTheCloseThatCrossesTheThreshold() {
        // achat a 100 le jour 1, stop a 5 % -> seuil 95
        double[] closes = {100, 100, 100, 97, 94, 96, 90};
        Run r = run(closes, signals(7, new int[] {0}, new int[0]), settings(0, 0, 0.05, null, 1));

        assertEquals(1, r.trades().size());
        Trade t = r.trades().get(0);
        assertEquals(ExitReason.STOP_LOSS, t.reason());
        assertEquals(START.plusDays(4), t.exitDate(), "97 ne declenche pas le stop, 94 oui");
        assertEquals(94, t.exitPrice(), EPS, "sortie a la cloture (94), donc sous le seuil de 95 : limite connue");
        assertEquals(-0.06, t.returnRate(), EPS);
        assertArrayEquals(new double[] {1000, 1000, 1000, 970, 940, 940, 940}, r.equity(), EPS);
    }

    @Test
    void takeProfitExitsAtTheCloseThatCrossesTheThreshold() {
        // achat a 100 le jour 1, take-profit a 10 % -> seuil 110
        double[] closes = {100, 100, 105, 112, 120, 90};
        Run r = run(closes, signals(6, new int[] {0}, new int[0]), settings(0, 0, null, 0.10, 1));

        Trade t = r.trades().get(0);
        assertEquals(ExitReason.TAKE_PROFIT, t.reason());
        assertEquals(START.plusDays(3), t.exitDate());
        assertEquals(112, t.exitPrice(), EPS);
        assertEquals(1120, r.equity()[5], EPS, "la chute a 90 ne touche plus le portefeuille");
    }

    @Test
    void stopLossIsMeasuredFromTheRealEntryPrice() {
        // glissement 1 % : achat a 101 -> seuil du stop a 5 % = 95,95
        double[] closes = {100, 100, 96, 95.9, 95.9};
        Run r = run(closes, signals(5, new int[] {0}, new int[0]), settings(0, 0.01, 0.05, null, 1));
        Trade t = r.trades().get(0);
        assertEquals(ExitReason.STOP_LOSS, t.reason());
        assertEquals(START.plusDays(3), t.exitDate(), "96 > 95,95 : on garde ; 95,9 <= 95,95 : on sort");
    }

    @Test
    void stopLossHasPriorityOverASellSignal() {
        double[] closes = {100, 100, 90, 90};
        Run r = run(closes, signals(4, new int[] {0}, new int[] {1}), settings(0, 0, 0.05, null, 1));
        assertEquals(ExitReason.STOP_LOSS, r.trades().get(0).reason());
    }

    @Test
    void afterAStopTheNextBuySignalIsExecutedTheFollowingDay() {
        double[] closes = {100, 100, 94, 94, 94, 94};
        Run r = run(closes, signals(6, new int[] {0, 1, 2, 3, 4}, new int[0]), settings(0, 0, 0.05, null, 1));
        assertEquals(2, r.trades().size());
        assertEquals(START.plusDays(2), r.trades().get(0).exitDate());
        assertEquals(START.plusDays(3), r.trades().get(1).entryDate(), "pas de rachat le jour meme du stop");
    }

    // ===================== Cas limites =====================

    @Test
    void withoutSignalThereIsNoTradeAndTheCapitalDoesNotMove() {
        double[] closes = {100, 105, 95, 110, 90};
        Run r = run(closes, signals(5, new int[0], new int[0]), settings(0.001, 0.01, 0.05, 0.1, 1));
        assertTrue(r.trades().isEmpty());
        assertArrayEquals(new double[] {1000, 1000, 1000, 1000, 1000}, r.equity(), EPS);
    }

    @Test
    void sellSignalsAreIgnoredWithoutAnOpenPosition() {
        double[] closes = {100, 105, 95, 110, 90};
        Run r = run(closes, signals(5, new int[0], new int[] {0, 1, 2, 3}), noCost());
        assertTrue(r.trades().isEmpty(), "achat uniquement : pas de vente a decouvert");
    }

    @Test
    void anOpenPositionIsClosedOnTheLastDay() {
        double[] closes = {100, 100, 110, 120};
        Run r = run(closes, signals(4, new int[] {0}, new int[0]), noCost());
        Trade t = r.trades().get(0);
        assertEquals(ExitReason.END_OF_PERIOD, t.reason());
        assertEquals(START.plusDays(3), t.exitDate());
        assertEquals(1200, r.equity()[3], EPS);
    }

    @Test
    void noPositionIsOpenedOnTheLastDay() {
        double[] closes = {100, 100, 110, 120};
        Run r = run(closes, signals(4, new int[] {2}, new int[0]), noCost());
        assertTrue(r.trades().isEmpty(), "un achat le dernier jour ne pourrait pas etre revendu");
    }

    @Test
    void buyAndHoldReferenceIsFullyInvestedFromTheFirstCloseWithoutFees() {
        double[] closes = {50, 100, 110, 99, 121};
        Run r = BacktestEngine.run(dates(5), closes, signals(5, new int[0], new int[0]), 1, 4,
                settings(0.001, 0.01, null, null, 1));
        assertArrayEquals(new double[] {1000, 1100, 990, 1210}, r.buyHold(), EPS);
    }

    @Test
    void rejectsAnInvalidWindow() {
        double[] closes = {100, 101, 102};
        Signal[] s = signals(3, new int[0], new int[0]);
        assertThrows(IllegalArgumentException.class, () -> BacktestEngine.run(dates(3), closes, s, 2, 2, noCost()));
        assertThrows(IllegalArgumentException.class, () -> BacktestEngine.run(dates(3), closes, s, 0, 3, noCost()));
    }
}
