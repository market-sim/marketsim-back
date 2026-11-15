package tn.esprit.marketsim.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Parametres des strategies : valeurs par defaut, nettoyage et validation. */
class StrategyRulesTest {

    private static StrategyParams params(Integer fast, Integer slow, Integer signal, Integer period,
                                         Double buyBelow, Double sellAbove, Double bandK) {
        StrategyParams p = new StrategyParams();
        p.setFastPeriod(fast);
        p.setSlowPeriod(slow);
        p.setSignalPeriod(signal);
        p.setPeriod(period);
        p.setBuyBelow(buyBelow);
        p.setSellAbove(sellAbove);
        p.setBandK(bandK);
        return p;
    }

    private static void assertRejected(StrategyType type, StrategyParams p) {
        assertThrows(IllegalArgumentException.class, () -> StrategyRules.normalize(type, p));
    }

    @Test
    void missingParametersTakeTheDefaultValues() {
        StrategyParams sma = StrategyRules.normalize(StrategyType.SMA_CROSS, null);
        assertEquals(20, sma.getFastPeriod());
        assertEquals(50, sma.getSlowPeriod());

        StrategyParams ema = StrategyRules.normalize(StrategyType.EMA_CROSS, new StrategyParams());
        assertEquals(12, ema.getFastPeriod());
        assertEquals(26, ema.getSlowPeriod());

        StrategyParams rsi = StrategyRules.normalize(StrategyType.RSI, null);
        assertEquals(14, rsi.getPeriod());
        assertEquals(30.0, rsi.getBuyBelow());
        assertEquals(70.0, rsi.getSellAbove());

        StrategyParams macd = StrategyRules.normalize(StrategyType.MACD, null);
        assertEquals(12, macd.getFastPeriod());
        assertEquals(26, macd.getSlowPeriod());
        assertEquals(9, macd.getSignalPeriod());

        StrategyParams bb = StrategyRules.normalize(StrategyType.BOLLINGER, null);
        assertEquals(20, bb.getPeriod());
        assertEquals(2.0, bb.getBandK());
    }

    @Test
    void givenParametersAreKept() {
        StrategyParams p = StrategyRules.normalize(StrategyType.SMA_CROSS, params(10, 30, null, null, null, null, null));
        assertEquals(10, p.getFastPeriod());
        assertEquals(30, p.getSlowPeriod());
    }

    @Test
    void parametersOfOtherTypesAreErased() {
        StrategyParams all = params(10, 30, 9, 14, 25.0, 75.0, 2.5);

        StrategyParams sma = StrategyRules.normalize(StrategyType.SMA_CROSS, all);
        assertNull(sma.getSignalPeriod());
        assertNull(sma.getPeriod());
        assertNull(sma.getBuyBelow());
        assertNull(sma.getBandK());

        StrategyParams hold = StrategyRules.normalize(StrategyType.BUY_AND_HOLD, all);
        assertNull(hold.getFastPeriod());
        assertNull(hold.getSlowPeriod());
        assertNull(hold.getPeriod());
        assertNull(hold.getSellAbove());
    }

    @Test
    void rejectsIncoherentParameters() {
        assertRejected(StrategyType.SMA_CROSS, params(50, 20, null, null, null, null, null));   // rapide > lente
        assertRejected(StrategyType.EMA_CROSS, params(20, 20, null, null, null, null, null));   // rapide = lente
        assertRejected(StrategyType.SMA_CROSS, params(1, 20, null, null, null, null, null));    // periode < 2
        assertRejected(StrategyType.SMA_CROSS, params(20, 501, null, null, null, null, null));  // periode > 500
        assertRejected(StrategyType.MACD, params(26, 12, 9, null, null, null, null));
        assertRejected(StrategyType.MACD, params(12, 26, 0, null, null, null, null));
        assertRejected(StrategyType.RSI, params(null, null, null, 14, 70.0, 30.0, null));       // seuils inverses
        assertRejected(StrategyType.RSI, params(null, null, null, 14, 0.0, 70.0, null));
        assertRejected(StrategyType.RSI, params(null, null, null, 14, 30.0, 100.0, null));
        assertRejected(StrategyType.RSI, params(null, null, null, 14, Double.NaN, 70.0, null));
        assertRejected(StrategyType.BOLLINGER, params(null, null, null, 20, null, null, 0.0));
        assertRejected(StrategyType.BOLLINGER, params(null, null, null, 20, null, null, 11.0));
        assertRejected(StrategyType.BOLLINGER, params(null, null, null, 1, null, null, 2.0));
    }

    @Test
    void describesTheStrategyAsShortText() {
        assertEquals("SMA(20),SMA(50)", describe(StrategyType.SMA_CROSS, null));
        assertEquals("EMA(12),EMA(26)", describe(StrategyType.EMA_CROSS, null));
        assertEquals("RSI(14)", describe(StrategyType.RSI, null));
        assertEquals("MACD(12,26,9)", describe(StrategyType.MACD, null));
        assertEquals("BB(20,2)", describe(StrategyType.BOLLINGER, null));
        assertEquals("BB(20,2.5)", describe(StrategyType.BOLLINGER, params(null, null, null, 20, null, null, 2.5)));
        assertEquals("Buy & Hold", describe(StrategyType.BUY_AND_HOLD, null));
    }

    private static String describe(StrategyType type, StrategyParams raw) {
        return StrategyRules.describe(type, StrategyRules.normalize(type, raw));
    }
}
