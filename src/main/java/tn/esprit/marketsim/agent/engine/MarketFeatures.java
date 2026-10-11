package tn.esprit.marketsim.agent.engine;

/**
 * Variables explicatives observees par l'agent a un instant t (toutes normalisees, ~[-1.5, 1.5],
 * 0 = marche "neutre"). Le 0 sert de reference pour les explications XAI.
 *
 * @param momentum      rendement des 20 derniers jours, en ecarts-types (z-score / 2)
 * @param trend         ecart moyenne mobile 10j vs 30j, normalise par la volatilite
 * @param rsi           (RSI14 - 50) / 50 : +1 = surachat, -1 = survente
 * @param meanReversion -(z-score de Bollinger 20j) / 2 : positif = prix anormalement bas
 * @param volatility    volatilite realisee 20j / volatilite normale - 1 : positif = marche agite
 */
public record MarketFeatures(double momentum, double trend, double rsi, double meanReversion, double volatility) {

    public static final String[] NAMES = {"momentum", "trend", "rsi", "meanReversion", "volatility"};
    public static final String[] LABELS = {
            "Momentum 20j", "Tendance (MM10/MM30)", "RSI 14", "Retour à la moyenne (Bollinger)", "Volatilité"};

    /** Nombre minimum de prix necessaires avant de pouvoir calculer les indicateurs. */
    public static final int WARMUP = 31;

    public double[] toArray() {
        return new double[] {momentum, trend, rsi, meanReversion, volatility};
    }

    /** Calcule les indicateurs sur prices[0..t] (t >= 30). normalSigma = volatilite annuelle de reference. */
    public static MarketFeatures compute(double[] prices, int t, double normalSigma) {
        if (t < WARMUP - 1) {
            throw new IllegalArgumentException("Historique trop court pour calculer les indicateurs");
        }
        double vol20 = Math.max(stdLogReturns(prices, t, 20), 1e-6);

        double momentum = clip(Math.log(prices[t] / prices[t - 20]) / (vol20 * Math.sqrt(20)) / 2, 1.5);

        double sma10 = sma(prices, t, 10);
        double sma30 = sma(prices, t, 30);
        double trend = clip((sma10 / sma30 - 1) / (vol20 * Math.sqrt(10)) / 2, 1.5);

        double rsi = (rsi14(prices, t) - 50) / 50;

        double sma20 = sma(prices, t, 20);
        double sd20 = Math.max(stdPrices(prices, t, 20, sma20), 1e-9);
        double meanReversion = clip(-(prices[t] - sma20) / sd20 / 2, 1.5);

        double annualVol = vol20 * Math.sqrt(PathGenerator.TRADING_DAYS);
        double volatility = Math.max(-1, Math.min(2, annualVol / normalSigma - 1));

        return new MarketFeatures(momentum, trend, rsi, meanReversion, volatility);
    }

    private static double sma(double[] p, int t, int n) {
        double s = 0;
        for (int i = t - n + 1; i <= t; i++) s += p[i];
        return s / n;
    }

    private static double stdPrices(double[] p, int t, int n, double mean) {
        double s = 0;
        for (int i = t - n + 1; i <= t; i++) s += (p[i] - mean) * (p[i] - mean);
        return Math.sqrt(s / n);
    }

    private static double stdLogReturns(double[] p, int t, int n) {
        double mean = 0;
        for (int i = t - n + 1; i <= t; i++) mean += Math.log(p[i] / p[i - 1]);
        mean /= n;
        double s = 0;
        for (int i = t - n + 1; i <= t; i++) {
            double r = Math.log(p[i] / p[i - 1]) - mean;
            s += r * r;
        }
        return Math.sqrt(s / (n - 1));
    }

    private static double rsi14(double[] p, int t) {
        double gain = 0;
        double loss = 0;
        for (int i = t - 13; i <= t; i++) {
            double d = p[i] - p[i - 1];
            if (d > 0) gain += d;
            else loss -= d;
        }
        if (gain + loss == 0) return 50;
        return 100 * gain / (gain + loss);
    }

    private static double clip(double v, double bound) {
        return Math.max(-bound, Math.min(bound, v));
    }
}
