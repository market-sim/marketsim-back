package tn.esprit.marketsim.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class PriceStepsTest {

    private static final double DT = PriceSteps.dtYears(3000, 60);

    @Test
    void pasDeTempsAccelere() {
        // 3 s × 60 = 180 s de marche sur une annee de 252 jours de 24 h
        assertEquals(180.0 / (252 * 86400), DT, 1e-15);
    }

    @Test
    void marcheAleatoireBorneeEtJamaisNegative() {
        double s = 0.70 * Math.sqrt(DT);
        double p = 100;
        Random r = new Random(1);
        for (int i = 0; i < 100_000; i++) {
            double next = PriceSteps.gbm(p, 0.70, DT, r.nextGaussian() * 10); // chocs exageres
            assertTrue(next > 0);
            // un pas ne depasse jamais 4 ecarts-types
            assertTrue(Math.abs(Math.log(next / p)) <= PriceSteps.MAX_Z * s + 0.5 * s * s + 1e-12);
            p = next;
        }
        assertTrue(PriceSteps.gbm(0, 0.3, DT, -4) >= PriceSteps.MIN_PRICE);
        assertTrue(PriceSteps.gbm(1e-9, 5, 1, -4) >= PriceSteps.MIN_PRICE);
        assertEquals(50, PriceSteps.gbm(50, 0.3, DT, Double.NaN), 1e-3);
    }

    @Test
    void sansDeriveLEsperanceResteLaMeme() {
        Random r = new Random(7);
        double sum = 0;
        int n = 200_000;
        for (int i = 0; i < n; i++) sum += PriceSteps.gbm(100, 0.30, DT * 100, r.nextGaussian());
        assertEquals(100, sum / n, 0.05);
    }

    @Test
    void ecartAuCoursReelRevientVersZeroEtResteBorne() {
        Random r = new Random(3);
        double x = 0.05;
        double max = 0;
        for (int i = 0; i < 50_000; i++) {
            x = PriceSteps.deviation(x, 0.30, DT, 0.05, 0.05, r.nextGaussian());
            max = Math.max(max, Math.abs(x));
        }
        assertTrue(max <= 0.05);
        // sans choc, le rappel ramene l'ecart vers 0
        double y = 0.04;
        for (int i = 0; i < 200; i++) y = PriceSteps.deviation(y, 0.30, DT, 0.05, 0.05, 0);
        assertTrue(Math.abs(y) < 0.001);
        assertEquals(110.0, PriceSteps.anchored(110, 0), 1e-9);
        assertTrue(PriceSteps.anchored(0, -1) > 0);
    }

    @Test
    void variationDepuisLOuverture() {
        assertEquals(2.5, PriceSteps.changePct(102.5, 100));
        assertEquals(-1.23, PriceSteps.changePct(98.77, 100));
        assertEquals(0, PriceSteps.changePct(10, 0));
    }
}
