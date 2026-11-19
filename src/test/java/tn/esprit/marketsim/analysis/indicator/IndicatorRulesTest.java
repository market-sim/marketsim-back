package tn.esprit.marketsim.analysis.indicator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.analysis.StrategyRules;

/** Regles communes a l'endpoint des indicateurs et aux configurations enregistrees. */
class IndicatorRulesTest {

    private static String messageOf(Runnable call) {
        return assertThrows(IllegalArgumentException.class, call::run).getMessage();
    }

    @Test
    void aPeriodIsAnIntegerOfAtLeastTwo() {
        assertEquals(2, IndicatorRules.period("sma", 2));
        assertEquals(200, IndicatorRules.period("sma", 200.0));
        assertEquals("sma : la période doit être un entier supérieur ou égal à 2", messageOf(() -> IndicatorRules.period("sma", 1)));
        assertTrue(messageOf(() -> IndicatorRules.period("ema", 20.5)).startsWith("ema"));
        assertTrue(messageOf(() -> IndicatorRules.period("rsi", -14)).startsWith("rsi"));
        assertTrue(messageOf(() -> IndicatorRules.period("rsi", Double.NaN)).startsWith("rsi"));
    }

    @Test
    void theFastPeriodMustBeShorterThanTheSlowOne() {
        assertDoesNotThrow(() -> IndicatorRules.fastBeforeSlow("macd", 12, 26));
        assertEquals("macd : la période rapide doit être plus courte que la période lente",
                messageOf(() -> IndicatorRules.fastBeforeSlow("macd", 26, 12)));
        assertTrue(messageOf(() -> IndicatorRules.fastBeforeSlow("sma", 20, 20)).startsWith("sma"), "egalite refusee");
    }

    @Test
    void theBollingerMultiplierIsBetweenZeroAndTen() {
        assertEquals(2.0, IndicatorRules.bandK("bollinger", 2));
        assertEquals(10.0, IndicatorRules.bandK("bollinger", 10), "10 compris");
        assertEquals(0.1, IndicatorRules.bandK("bollinger", 0.1));
        assertEquals("bollinger : le multiplicateur doit être entre 0 et 10", messageOf(() -> IndicatorRules.bandK("bollinger", 0)));
        messageOf(() -> IndicatorRules.bandK("bollinger", 10.5));
        messageOf(() -> IndicatorRules.bandK("bollinger", -1));
        messageOf(() -> IndicatorRules.bandK("bollinger", Double.NaN));
        messageOf(() -> IndicatorRules.bandK("bollinger", Double.POSITIVE_INFINITY));
    }

    @Test
    void aSavedConfigurationUsesTheStrategyLimit() {
        assertEquals(StrategyRules.MAX_PERIOD, IndicatorRules.MAX_SAVED_PERIOD);
    }
}
