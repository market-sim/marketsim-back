package tn.esprit.marketsim.analysis.indicator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;

/** Indicateurs techniques : chaque formule est verifiee sur de petites series calculees a la main. */
class IndicatorsTest {

    private static final double NAN = Double.NaN;
    private static final double EPS = 1e-9;

    private static double[] prices(int n, IntToDoubleFunction f) {
        double[] p = new double[n];
        for (int i = 0; i < n; i++) p[i] = f.applyAsDouble(i);
        return p;
    }

    /** Cours qui oscille autour d'une tendance haussiere (deterministe). */
    private static double[] wavy(int n) {
        return prices(n, i -> 100 + 0.2 * i + 5 * Math.sin(i / 4.0));
    }

    private static long warmup(double[] values) {
        return Arrays.stream(values).filter(Double::isNaN).count();
    }

    // ===================== SMA =====================

    @Test
    void smaIsTheMeanOfTheLastNCloses() {
        double[] sma = Indicators.sma(new double[] {1, 2, 3, 4, 5}, 3);
        assertArrayEquals(new double[] {NAN, NAN, 2, 3, 4}, sma, EPS);
    }

    @Test
    void smaOfAConstantSeriesIsTheConstant() {
        double[] sma = Indicators.sma(prices(30, i -> 42), 10);
        assertEquals(9, warmup(sma), "n-1 jours de chauffe");
        for (int i = 9; i < 30; i++) assertEquals(42, sma[i], EPS);
    }

    @Test
    void seriesShorterThanThePeriodGivesOnlyNaN() {
        assertEquals(3, warmup(Indicators.sma(new double[] {1, 2, 3}, 5)));
        assertEquals(3, warmup(Indicators.ema(new double[] {1, 2, 3}, 5)));
        assertEquals(3, warmup(Indicators.rsi(new double[] {1, 2, 3}, 3)), "le RSI a besoin de n+1 prix");
    }

    // ===================== EMA =====================

    @Test
    void emaStartsWithTheSmaThenSmooths() {
        // n = 3 -> alpha = 0,5. Depart : (2+4+6)/3 = 4. Puis 0,5*8 + 0,5*4 = 6, puis 0,5*12 + 0,5*6 = 9.
        double[] ema = Indicators.ema(new double[] {2, 4, 6, 8, 12}, 3);
        assertArrayEquals(new double[] {NAN, NAN, 4, 6, 9}, ema, EPS);
    }

    @Test
    void emaReactsFasterThanSmaAfterAJump() {
        double[] p = prices(40, i -> i < 30 ? 100 : 120);   // saut de 100 a 120 au jour 30
        double ema = Indicators.ema(p, 10)[31];
        double sma = Indicators.sma(p, 10)[31];
        assertTrue(ema > sma, "l'EMA donne plus de poids aux prix recents");
        assertTrue(ema < 120 && sma > 100);
    }

    // ===================== RSI =====================

    @Test
    void rsiMatchesAHandComputedWilderExample() {
        // Variations : +2, -1, +3, -1, +3 avec n = 3.
        // Jour 3 : hausse moy. = 5/3, baisse moy. = 1/3            -> 100 * 5/6   = 83,3333
        // Jour 4 : hausse = (5/3*2 + 0)/3 = 10/9, baisse = 5/9     -> 100 * 10/15 = 66,6667
        // Jour 5 : hausse = (10/9*2 + 3)/3 = 47/27, baisse = 10/27 -> 100 * 47/57 = 82,4561
        double[] rsi = Indicators.rsi(new double[] {10, 12, 11, 14, 13, 16}, 3);
        assertEquals(3, warmup(rsi));
        assertEquals(100 * 5.0 / 6, rsi[3], EPS);
        assertEquals(100 * 10.0 / 15, rsi[4], EPS);
        assertEquals(100 * 47.0 / 57, rsi[5], EPS);
    }

    @Test
    void rsiExtremes() {
        double[] up = Indicators.rsi(prices(40, i -> 100 + i), 14);
        double[] down = Indicators.rsi(prices(40, i -> 100 - i), 14);
        double[] flat = Indicators.rsi(prices(40, i -> 100), 14);
        assertEquals(14, warmup(up), "premiere valeur au jour n");
        for (int i = 14; i < 40; i++) {
            assertEquals(100, up[i], EPS, "que des hausses");
            assertEquals(0, down[i], EPS, "que des baisses");
            assertEquals(50, flat[i], EPS, "serie plate : neutre");
        }
    }

    @Test
    void rsiStaysBetween0And100() {
        double[] rsi = Indicators.rsi(wavy(300), 14);
        for (int i = 14; i < rsi.length; i++) assertTrue(rsi[i] >= 0 && rsi[i] <= 100);
    }

    // ===================== MACD =====================

    @Test
    void macdOfAConstantSeriesIsZero() {
        Indicators.Macd m = Indicators.macd(prices(60, i -> 50), 12, 26, 9);
        assertEquals(25, warmup(m.macd()), "macd a partir du jour slow-1");
        assertEquals(33, warmup(m.signal()), "signal a partir du jour slow+signal-2");
        assertEquals(33, warmup(m.histogram()));
        for (int i = 33; i < 60; i++) {
            assertEquals(0, m.macd()[i], EPS);
            assertEquals(0, m.signal()[i], EPS);
            assertEquals(0, m.histogram()[i], EPS);
        }
    }

    @Test
    void macdIsFastEmaMinusSlowEmaAndHistogramIsMacdMinusSignal() {
        double[] p = wavy(200);
        Indicators.Macd m = Indicators.macd(p, 12, 26, 9);
        double[] fast = Indicators.ema(p, 12);
        double[] slow = Indicators.ema(p, 26);
        for (int i = 25; i < p.length; i++) assertEquals(fast[i] - slow[i], m.macd()[i], EPS);
        for (int i = 33; i < p.length; i++) assertEquals(m.macd()[i] - m.signal()[i], m.histogram()[i], EPS);
    }

    @Test
    void macdSignFollowsTheTrend() {
        Indicators.Macd up = Indicators.macd(prices(100, i -> 100 * Math.exp(0.01 * i)), 12, 26, 9);
        Indicators.Macd down = Indicators.macd(prices(100, i -> 100 * Math.exp(-0.01 * i)), 12, 26, 9);
        for (int i = 25; i < 100; i++) {
            assertTrue(up.macd()[i] > 0, "hausse : EMA rapide au-dessus de l'EMA lente");
            assertTrue(down.macd()[i] < 0, "baisse : EMA rapide sous l'EMA lente");
        }
    }

    @Test
    void macdRejectsFastNotShorterThanSlow() {
        assertThrows(IllegalArgumentException.class, () -> Indicators.macd(wavy(100), 26, 12, 9));
        assertThrows(IllegalArgumentException.class, () -> Indicators.macd(wavy(100), 12, 12, 9));
    }

    // ===================== Bollinger =====================

    @Test
    void bollingerMatchesAHandComputedExample() {
        // Moyenne = 5, ecart-type de population = 2 -> bandes a 5 +/- 2*2.
        Indicators.Bands b = Indicators.bollinger(new double[] {2, 4, 4, 4, 5, 5, 7, 9}, 8, 2);
        assertEquals(7, warmup(b.middle()));
        assertEquals(5, b.middle()[7], EPS);
        assertEquals(9, b.upper()[7], EPS);
        assertEquals(1, b.lower()[7], EPS);
    }

    @Test
    void bollingerBandsCollapseOnAConstantSeriesAndStayOrdered() {
        Indicators.Bands flat = Indicators.bollinger(prices(30, i -> 10), 20, 2);
        assertEquals(10, flat.upper()[29], EPS);
        assertEquals(10, flat.lower()[29], EPS);

        double[] p = wavy(200);
        Indicators.Bands b = Indicators.bollinger(p, 20, 2);
        double[] sma = Indicators.sma(p, 20);
        for (int i = 19; i < p.length; i++) {
            assertEquals(sma[i], b.middle()[i], EPS, "le milieu est la SMA");
            assertTrue(b.lower()[i] <= b.middle()[i] && b.middle()[i] <= b.upper()[i]);
        }
    }

    // ===================== Regles communes =====================

    /** Biais d'anticipation : changer les prix APRES le jour J ne doit modifier aucune valeur jusqu'a J. */
    @Test
    void noIndicatorLooksIntoTheFuture() {
        int j = 80;
        double[] a = wavy(120);
        double[] b = a.clone();
        for (int i = j + 1; i < b.length; i++) b[i] = a[i] * 3 + 7;   // futur completement different

        assertSamePast(Indicators.sma(a, 20), Indicators.sma(b, 20), j);
        assertSamePast(Indicators.ema(a, 20), Indicators.ema(b, 20), j);
        assertSamePast(Indicators.rsi(a, 14), Indicators.rsi(b, 14), j);
        Indicators.Macd ma = Indicators.macd(a, 12, 26, 9);
        Indicators.Macd mb = Indicators.macd(b, 12, 26, 9);
        assertSamePast(ma.macd(), mb.macd(), j);
        assertSamePast(ma.signal(), mb.signal(), j);
        assertSamePast(ma.histogram(), mb.histogram(), j);
        Indicators.Bands ba = Indicators.bollinger(a, 20, 2);
        Indicators.Bands bb = Indicators.bollinger(b, 20, 2);
        assertSamePast(ba.upper(), bb.upper(), j);
        assertSamePast(ba.lower(), bb.lower(), j);
    }

    private static void assertSamePast(double[] a, double[] b, int j) {
        assertArrayEquals(Arrays.copyOfRange(a, 0, j + 1), Arrays.copyOfRange(b, 0, j + 1), 0.0);
    }

    @Test
    void outputHasTheSameLengthAsThePrices() {
        double[] p = wavy(90);
        assertEquals(90, Indicators.sma(p, 20).length);
        assertEquals(90, Indicators.ema(p, 20).length);
        assertEquals(90, Indicators.rsi(p, 14).length);
        assertEquals(90, Indicators.macd(p, 12, 26, 9).histogram().length);
        assertEquals(90, Indicators.bollinger(p, 20, 2).upper().length);
    }

    @Test
    void rejectsInvalidPeriods() {
        assertThrows(IllegalArgumentException.class, () -> Indicators.sma(wavy(50), 0));
        assertThrows(IllegalArgumentException.class, () -> Indicators.ema(wavy(50), -1));
        assertThrows(IllegalArgumentException.class, () -> Indicators.rsi(wavy(50), 0));
        assertThrows(IllegalArgumentException.class, () -> Indicators.bollinger(wavy(50), 20, 0));
    }
}
