package tn.esprit.marketsim.agent.replay;

import java.util.SplittableRandom;

/**
 * Bougies journalieres du replay. Le marche simule ne produit qu'un cours de cloture par jour ; l'ouverture est la
 * cloture de la veille et les extremes du jour (haut / bas) sont tires par un <b>pont brownien</b> entre l'ouverture
 * et la cloture : une trajectoire intra-journaliere simulee qui part de l'ouverture, finit exactement a la cloture,
 * avec la volatilite de l'actif. Le tirage est deterministe (graine + jour) : rejouer la meme partie donne les memes bougies.
 */
public final class Candles {

    static final int STEPS = 24;
    /** Part de la volatilite journaliere utilisee pour l'excursion intra-journaliere. */
    static final double INTRADAY_VOL = 0.9;

    private Candles() {
    }

    /** @return {haut, bas} du jour, toujours encadrant l'ouverture et la cloture */
    public static double[] extremes(double open, double close, double dailySigma, long seed, int day) {
        SplittableRandom rng = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + 31L * day + 7);
        double a = Math.log(open);
        double b = Math.log(close);
        double step = dailySigma * INTRADAY_VOL / Math.sqrt(STEPS);

        double[] walk = new double[STEPS + 1];
        for (int k = 1; k <= STEPS; k++) walk[k] = walk[k - 1] + step * rng.nextGaussian();
        double high = Math.max(open, close);
        double low = Math.min(open, close);
        for (int k = 1; k < STEPS; k++) {
            double bridge = walk[k] - (double) k / STEPS * walk[STEPS];     // pont : revient a zero en fin de journee
            double price = Math.exp(a + (b - a) * k / STEPS + bridge);
            high = Math.max(high, price);
            low = Math.min(low, price);
        }
        return new double[] {high, low};
    }
}
