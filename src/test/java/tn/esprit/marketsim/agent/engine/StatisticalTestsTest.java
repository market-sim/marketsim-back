package tn.esprit.marketsim.agent.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.tournament.TournamentServiceTestAccess;

/** Verifie les tests statistiques du tournoi contre des valeurs de reference (tables / R). */
class StatisticalTestsTest {

    @Test
    void studentPValuesMatchTables() {
        // valeurs de reference : 2 * pt(-|t|, df) dans R
        assertEquals(0.0734, Stats.studentTwoSidedP(2.0, 10), 1e-4);
        assertEquals(0.0500, Stats.studentTwoSidedP(2.228139, 10), 1e-5);
        assertEquals(0.0100, Stats.studentTwoSidedP(2.575829, 1e6), 1e-4); // -> loi normale
        assertEquals(1.0, Stats.studentTwoSidedP(0, 30), 1e-12);
    }

    @Test
    void normalCdf() {
        assertEquals(0.975, Stats.normalCdf(1.959964), 1e-6);
        assertEquals(0.5, Stats.normalCdf(0), 1e-7);
    }

    @Test
    void holmBonferroni() {
        // exemple classique : p = 0.01, 0.04, 0.03, 0.005 (m = 4)
        double[] adjusted = Stats.holm(new double[] {0.01, 0.04, 0.03, 0.005});
        assertArrayEquals(new double[] {0.03, 0.06, 0.06, 0.02}, adjusted, 1e-12);
    }

    @Test
    void pairedComparisonDetectsRealDifferenceButNotNoise() {
        SplittableRandom rng = new SplittableRandom(11);
        int n = 1000;
        double[] market = new double[n];
        double[] better = new double[n];
        double[] same = new double[n];
        for (int i = 0; i < n; i++) {
            market[i] = rng.nextGaussian() * 0.2;                  // bruit commun (le "marche")
            better[i] = market[i] + 0.01 + rng.nextGaussian() * 0.01; // +1 point en moyenne
            same[i] = market[i] + rng.nextGaussian() * 0.01;         // aucune difference reelle
        }
        double[] real = TournamentServiceTestAccess.compare(better, market, new SplittableRandom(1));
        double[] noise = TournamentServiceTestAccess.compare(same, market, new SplittableRandom(2));

        assertTrue(real[4] < 1e-6, "difference reelle detectee (p t-test)");
        assertTrue(real[1] > 0, "IC bootstrap ne contient pas 0");
        assertTrue(real[9] > 100, "nombres aleatoires communs : variance divisee par plus de 100");
        assertTrue(noise[4] > 0.01, "pas de faux positif sur du bruit");
        assertTrue(noise[1] < 0 && noise[2] > 0, "IC bootstrap contient 0");
    }
}
