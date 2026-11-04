package tn.esprit.marketsim.agent.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.replay.ReplayService.Candle;

/** Bougies du replay : toujours coherentes (haut >= ouverture, cloture ; bas <=), deterministes, proportionnelles a la volatilite. */
class CandlesTest {

    @Test
    void highAndLowAlwaysFrameOpenAndClose() {
        for (int day = 0; day < 2000; day++) {
            double open = 100 + day % 37;
            double close = open * (1 + ((day * 7919) % 200 - 100) / 2500.0);   // de -4 % a +4 %
            double[] hl = Candles.extremes(open, close, 0.019, 42, day);
            assertTrue(hl[0] >= Math.max(open, close) - 1e-12, "haut jour " + day);
            assertTrue(hl[1] <= Math.min(open, close) + 1e-12, "bas jour " + day);
            assertTrue(hl[1] > 0, "prix positifs");
        }
    }

    @Test
    void sameSeedAndDayGiveTheSameCandleButAnotherDayDiffers() {
        assertArrayEquals(Candles.extremes(100, 101, 0.02, 7, 12), Candles.extremes(100, 101, 0.02, 7, 12), 0.0);
        assertNotEquals(Candles.extremes(100, 101, 0.02, 7, 12)[0], Candles.extremes(100, 101, 0.02, 7, 13)[0]);
        assertNotEquals(Candles.extremes(100, 101, 0.02, 7, 12)[0], Candles.extremes(100, 101, 0.02, 8, 12)[0]);
    }

    @Test
    void theRangeGrowsWithVolatility() {
        double calm = 0;
        double wild = 0;
        for (int day = 0; day < 500; day++) {
            double[] c = Candles.extremes(100, 100.2, 0.004, 1, day);
            double[] w = Candles.extremes(100, 100.2, 0.045, 1, day);
            calm += (c[0] - c[1]) / 100;
            wild += (w[0] - w[1]) / 100;
        }
        assertTrue(wild > 5 * calm, "mèches bien plus longues quand la volatilité est 11 fois plus forte : " + calm + " vs " + wild);
        assertTrue(calm / 500 < 0.02, "un marché calme garde des bougies fines");
    }

    @Test
    void aFlatDayWithNoVolatilityIsAFlatCandle() {
        double[] hl = Candles.extremes(100, 100, 0, 1, 1);
        assertEquals(100, hl[0], 1e-9);
        assertEquals(100, hl[1], 1e-9);
    }

    @Test
    void replayCandleKeepsTheMarketCloseAndTheEveningBeforeAsOpen() {
        double[] path = {10, 10.5, 10.2, 11, 10.8};
        Candle c = ReplayService.candle(path, 3, 0.02, 5, 3);
        assertEquals(10.2, c.open(), 1e-9, "ouverture = cloture de la veille");
        assertEquals(11, c.close(), 1e-9, "cloture = cours du marche simule");
        assertTrue(c.high() >= 11 && c.low() <= 10.2);
    }
}
