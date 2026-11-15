package tn.esprit.marketsim.analysis.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tn.esprit.marketsim.analysis.backtest.Signal.BUY;
import static tn.esprit.marketsim.analysis.backtest.Signal.HOLD;
import static tn.esprit.marketsim.analysis.backtest.Signal.SELL;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyRules;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.indicator.Indicators;

/** Signaux des strategies : chaque regle est verifiee sur une petite serie calculee a la main. */
class SignalsTest {

    private static StrategyParams cross(int fast, int slow) {
        StrategyParams p = new StrategyParams();
        p.setFastPeriod(fast);
        p.setSlowPeriod(slow);
        return p;
    }

    private static double[] wavy(int n) {
        double[] p = new double[n];
        for (int i = 0; i < n; i++) p[i] = 100 + 0.2 * i + 5 * Math.sin(i / 4.0);
        return p;
    }

    private static long count(Signal[] signals, Signal wanted) {
        return Arrays.stream(signals).filter(s -> s == wanted).count();
    }

    @Test
    void smaCrossBuysWhenFastGoesAboveSlowAndSellsWhenItGoesBackBelow() {
        // indice :      0   1  2  3  4  5   6  7  8  9
        double[] p = {10, 9, 8, 7, 8, 9, 10, 9, 8, 7};
        // SMA(2) :      -  9.5 8.5 7.5 7.5  8.5 9.5 9.5   8.5 7.5
        // SMA(3) :      -   -  9   8   7.67 8   9   9.33  9   8
        // rapide > lente a partir du jour 5 (achat), rapide < lente a partir du jour 8 (vente)
        Signal[] s = Signals.compute(StrategyType.SMA_CROSS, cross(2, 3), p, 0);
        assertArrayEquals(new Signal[] {HOLD, HOLD, HOLD, HOLD, HOLD, BUY, HOLD, HOLD, SELL, HOLD}, s);
    }

    @Test
    void emaCrossSignalsAreExactlyTheCrossingDays() {
        double[] p = wavy(200);
        Signal[] s = Signals.compute(StrategyType.EMA_CROSS, cross(5, 20), p, 0);
        double[] fast = Indicators.ema(p, 5);
        double[] slow = Indicators.ema(p, 20);
        assertTrue(count(s, BUY) > 0 && count(s, SELL) > 0, "la serie oscille : il y a des croisements");
        for (int i = 20; i < p.length; i++) {
            boolean up = fast[i] > slow[i] && fast[i - 1] <= slow[i - 1];
            boolean down = fast[i] < slow[i] && fast[i - 1] >= slow[i - 1];
            assertEquals(up ? BUY : down ? SELL : HOLD, s[i], "jour " + i);
        }
    }

    @Test
    void macdSignalsAreTheCrossingsOfTheSignalLine() {
        double[] p = wavy(200);
        StrategyParams params = cross(3, 6);
        params.setSignalPeriod(4);
        Signal[] s = Signals.compute(StrategyType.MACD, params, p, 0);
        Indicators.Macd m = Indicators.macd(p, 3, 6, 4);
        assertTrue(count(s, BUY) > 0 && count(s, SELL) > 0);
        for (int i = 0; i < p.length; i++) {
            if (s[i] == BUY) assertTrue(m.macd()[i] > m.signal()[i] && m.macd()[i - 1] <= m.signal()[i - 1]);
            if (s[i] == SELL) assertTrue(m.macd()[i] < m.signal()[i] && m.macd()[i - 1] >= m.signal()[i - 1]);
        }
    }

    @Test
    void rsiBuysWhenOversoldAndSellsWhenOverbought() {
        // RSI(2) de Wilder : -, -, 100, 100, 50, 25, 12.5, 56.25, 78.1
        double[] p = {10, 11, 12, 13, 12, 11, 10, 11, 12};
        StrategyParams params = new StrategyParams();
        params.setPeriod(2);
        params.setBuyBelow(30.0);
        params.setSellAbove(70.0);
        Signal[] s = Signals.compute(StrategyType.RSI, params, p, 0);
        assertArrayEquals(new Signal[] {HOLD, HOLD, SELL, SELL, HOLD, BUY, BUY, HOLD, SELL}, s);
    }

    @Test
    void bollingerBuysBelowTheLowerBandAndSellsBackAtTheMean() {
        // Bollinger(3, k = 1)
        // jour 2 : (10,10,10) moyenne 10, cloture 10 = moyenne           -> vente
        // jour 3 : (10,10,7)  moyenne 9, ecart-type 1.414, bande basse 7.59, cloture 7 -> achat
        // jour 4 : (10,7,10)  moyenne 9, cloture 10 >= moyenne           -> vente
        double[] p = {10, 10, 10, 7, 10};
        StrategyParams params = new StrategyParams();
        params.setPeriod(3);
        params.setBandK(1.0);
        Signal[] s = Signals.compute(StrategyType.BOLLINGER, params, p, 0);
        assertArrayEquals(new Signal[] {HOLD, HOLD, SELL, BUY, SELL}, s);
    }

    @Test
    void buyAndHoldBuysOnlyOnTheFirstDayOfThePeriod() {
        Signal[] s = Signals.compute(StrategyType.BUY_AND_HOLD, new StrategyParams(), wavy(50), 12);
        assertEquals(1, count(s, BUY));
        assertEquals(0, count(s, SELL));
        assertEquals(BUY, s[12]);
    }

    @Test
    void noSignalBeforeTheWarmupIsOver() {
        double[] p = wavy(300);
        for (StrategyType type : StrategyType.values()) {
            if (type == StrategyType.BUY_AND_HOLD) continue;
            StrategyParams params = StrategyRules.normalize(type, null);
            int warmup = Signals.warmup(type, params);
            Signal[] s = Signals.compute(type, params, p, 0);
            for (int i = 0; i < warmup - 1; i++) assertEquals(HOLD, s[i], type + " jour " + i);
        }
    }

    @Test
    void warmupIsTheNumberOfClosesNeededForTheFirstSignal() {
        assertEquals(51, Signals.warmup(StrategyType.SMA_CROSS, StrategyRules.normalize(StrategyType.SMA_CROSS, null)));
        assertEquals(27, Signals.warmup(StrategyType.EMA_CROSS, StrategyRules.normalize(StrategyType.EMA_CROSS, null)));
        assertEquals(35, Signals.warmup(StrategyType.MACD, StrategyRules.normalize(StrategyType.MACD, null)));
        assertEquals(15, Signals.warmup(StrategyType.RSI, StrategyRules.normalize(StrategyType.RSI, null)));
        assertEquals(20, Signals.warmup(StrategyType.BOLLINGER, StrategyRules.normalize(StrategyType.BOLLINGER, null)));
        assertEquals(1, Signals.warmup(StrategyType.BUY_AND_HOLD, new StrategyParams()));
    }

    /** Biais d'anticipation : changer les prix APRES le jour J ne modifie aucun signal jusqu'a J. */
    @Test
    void signalsNeverUseFuturePrices() {
        int j = 150;
        double[] a = wavy(300);
        double[] b = a.clone();
        for (int i = j + 1; i < b.length; i++) b[i] = a[i] * 0.5 + 3 * Math.cos(i);
        for (StrategyType type : StrategyType.values()) {
            StrategyParams params = StrategyRules.normalize(type, null);
            Signal[] sa = Signals.compute(type, params, a, 0);
            Signal[] sb = Signals.compute(type, params, b, 0);
            assertArrayEquals(Arrays.copyOfRange(sa, 0, j + 1), Arrays.copyOfRange(sb, 0, j + 1), type.name());
        }
    }
}
