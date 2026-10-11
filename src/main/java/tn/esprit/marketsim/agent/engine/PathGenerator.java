package tn.esprit.marketsim.agent.engine;

import java.util.random.RandomGenerator;

/**
 * Generateur de trajectoires de prix (pas journalier, dt = 1/252).
 *
 * <pre>
 * GBM              : ln(S[t+1]/S[t]) = (mu - sigma^2/2) dt + sigma sqrt(dt) Z
 * Jump-diffusion   : idem + somme de N sauts J ~ N(muJ, sigmaJ), N ~ Poisson(lambda dt),
 *                    avec drift compense (mu - lambda k) pour garder la meme esperance.
 * Regime switching : mu et sigma dependent d'un regime cache (haussier / baissier) qui suit
 *                    une chaine de Markov ; le drift moyen de long terme reste egal a mu.
 * </pre>
 *
 * Les chocs aleatoires sont tires une fois ({@link Shocks}) pour permettre les
 * <b>variables antithetiques</b> : la trajectoire jumelle utilise -Z (memes sauts, memes regimes),
 * ce qui reduit la variance de l'estimateur Monte Carlo.
 */
public final class PathGenerator {

    public static final int TRADING_DAYS = 252;
    public static final double DT = 1.0 / TRADING_DAYS;

    // ----- Regime switching -----
    /** Probabilite journaliere de rester en marche haussier (duree moyenne ~67 jours). */
    static final double STAY_BULL = 0.985;
    /** Probabilite journaliere de rester en marche baissier (duree moyenne ~40 jours). */
    static final double STAY_BEAR = 0.975;
    /** Ecarts de drift choisis pour que la moyenne stationnaire (62,5 % / 37,5 %) soit nulle. */
    static final double BULL_DRIFT = 0.20;
    static final double BEAR_DRIFT = -1.0 / 3;
    static final double BULL_VOL = 0.8;
    static final double BEAR_VOL = 1.5;

    private PathGenerator() {
    }

    /** Chocs browniens Z, somme des sauts et regime (true = baissier) pour chaque pas. */
    public record Shocks(double[] z, double[] jumps, boolean[] bear) {
    }

    public static Shocks drawShocks(int steps, AssetDynamics d, PriceModel model, RandomGenerator rng) {
        double[] z = new double[steps];
        double[] jumps = new double[steps];
        boolean[] bear = new boolean[steps];
        // regime initial tire selon la loi stationnaire de la chaine
        boolean inBear = rng.nextDouble() < (1 - STAY_BULL) / ((1 - STAY_BULL) + (1 - STAY_BEAR));
        for (int t = 0; t < steps; t++) {
            z[t] = rng.nextGaussian();
            if (model == PriceModel.JUMP_DIFFUSION) {
                int n = poisson(d.jumpIntensity() * DT, rng);
                for (int i = 0; i < n; i++) {
                    jumps[t] += d.jumpMean() + d.jumpStd() * rng.nextGaussian();
                }
            } else if (model == PriceModel.REGIME_SWITCHING) {
                double stay = inBear ? STAY_BEAR : STAY_BULL;
                if (rng.nextDouble() > stay) inBear = !inBear;
                bear[t] = inBear;
            }
        }
        return new Shocks(z, jumps, bear);
    }

    /** Construit une trajectoire a partir de chocs ; antithetic = utilise -Z. */
    public static double[] build(double s0, Shocks shocks, AssetDynamics d, PriceModel model, boolean antithetic) {
        int steps = shocks.z().length;
        double[] p = new double[steps + 1];
        p[0] = s0;
        double jumpCompensation = 0;
        if (model == PriceModel.JUMP_DIFFUSION) {
            double k = Math.exp(d.jumpMean() + 0.5 * d.jumpStd() * d.jumpStd()) - 1;
            jumpCompensation = d.jumpIntensity() * k;
        }
        double sign = antithetic ? -1 : 1;
        for (int t = 1; t <= steps; t++) {
            double mu = d.mu() - jumpCompensation;
            double sigma = d.sigma();
            if (model == PriceModel.REGIME_SWITCHING) {
                boolean bear = shocks.bear()[t - 1];
                mu += bear ? BEAR_DRIFT : BULL_DRIFT;
                sigma *= bear ? BEAR_VOL : BULL_VOL;
            }
            double logReturn = (mu - 0.5 * sigma * sigma) * DT + sigma * Math.sqrt(DT) * sign * shocks.z()[t - 1]
                    + shocks.jumps()[t - 1];
            p[t] = p[t - 1] * Math.exp(logReturn);
        }
        return p;
    }

    public static double[] generate(double s0, int steps, AssetDynamics d, PriceModel model, RandomGenerator rng) {
        return build(s0, drawShocks(steps, d, model, rng), d, model, false);
    }

    /** Algorithme de Knuth (lambda petit ici : lambda*dt << 1). */
    static int poisson(double lambda, RandomGenerator rng) {
        double l = Math.exp(-lambda);
        int k = 0;
        double p = 1;
        do {
            k++;
            p *= rng.nextDouble();
        } while (p > l);
        return k - 1;
    }
}
