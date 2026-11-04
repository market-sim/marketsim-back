package tn.esprit.marketsim.agent.engine;

/**
 * Scenario "et si ?" : un krach instantane au jour J, suivi d'une periode de volatilite accrue
 * (comme apres un vrai choc de marche : la panique dure plusieurs semaines).
 *
 * @param dropPct       baisse instantanee en fraction (0.20 = -20 %)
 * @param day           jour du krach dans l'horizon simule (0 = premier jour)
 * @param volMultiplier volatilite multipliee par ce facteur apres le krach (1 = inchangee)
 * @param volDays       duree du pic de volatilite en jours
 */
public record CrashScenario(double dropPct, int day, double volMultiplier, int volDays) {

    public CrashScenario {
        if (dropPct < 0 || dropPct >= 0.95) {
            throw new IllegalArgumentException("Le krach doit être entre 0 % et 95 %");
        }
        if (volMultiplier < 1 || volMultiplier > 5) {
            throw new IllegalArgumentException("Le multiplicateur de volatilité doit être entre 1 et 5");
        }
    }

    /**
     * Applique le scenario aux chocs d'une trajectoire : saut log(1 - drop) au jour du krach,
     * chocs browniens amplifies ensuite. Les memes chocs modifies servent aux deux trajectoires
     * antithetiques (le krach n'est pas "inverse").
     *
     * @param warmup nombre de jours d'historique avant le debut de l'horizon
     */
    public PathGenerator.Shocks apply(PathGenerator.Shocks shocks, int warmup) {
        double[] z = shocks.z().clone();
        double[] jumps = shocks.jumps().clone();
        int crashIndex = warmup + day - 1; // shocks[t-1] produit le prix t
        if (crashIndex < 0 || crashIndex >= z.length) {
            return shocks;
        }
        jumps[crashIndex] += Math.log(1 - dropPct);
        for (int t = crashIndex + 1; t < Math.min(z.length, crashIndex + 1 + volDays); t++) {
            z[t] *= volMultiplier;
        }
        return new PathGenerator.Shocks(z, jumps, shocks.bear());
    }
}
