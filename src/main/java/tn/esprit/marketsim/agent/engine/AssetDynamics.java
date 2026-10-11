package tn.esprit.marketsim.agent.engine;

import tn.esprit.marketsim.market.Asset;

/**
 * Parametres annualises de la dynamique d'un actif.
 *
 * @param mu            rendement moyen annuel (drift)
 * @param sigma         volatilite annuelle
 * @param jumpIntensity nombre moyen de sauts par an (lambda de Poisson)
 * @param jumpMean      moyenne du saut en log-rendement (negatif = krach)
 * @param jumpStd       ecart-type du saut
 */
public record AssetDynamics(double mu, double sigma, double jumpIntensity, double jumpMean, double jumpStd) {

    /** Calibration par defaut selon la classe d'actif. */
    public static AssetDynamics of(Asset.Type type) {
        return switch (type) {
            case STOCK -> new AssetDynamics(0.08, 0.30, 3.0, -0.04, 0.06);
            case CRYPTO -> new AssetDynamics(0.20, 0.70, 6.0, -0.08, 0.12);
            case FOREX -> new AssetDynamics(0.00, 0.08, 1.0, -0.01, 0.02);
            case COMMODITY -> new AssetDynamics(0.03, 0.25, 2.0, -0.05, 0.07);
            case BOND -> new AssetDynamics(0.03, 0.06, 0.5, -0.01, 0.01);
        };
    }

    /** Remplace drift et/ou volatilite si l'utilisateur les a fournis. */
    public AssetDynamics withOverrides(Double newMu, Double newSigma) {
        return new AssetDynamics(
                newMu != null ? newMu : mu,
                newSigma != null && newSigma > 0 ? newSigma : sigma,
                jumpIntensity, jumpMean, jumpStd);
    }

    public double dailySigma() {
        return sigma / Math.sqrt(PathGenerator.TRADING_DAYS);
    }
}
