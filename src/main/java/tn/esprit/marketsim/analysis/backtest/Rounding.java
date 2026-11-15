package tn.esprit.marketsim.analysis.backtest;

/** Module 3 : arrondis utilises pour l'affichage et le stockage des resultats (jamais pendant les calculs). */
final class Rounding {

    private Rounding() {
    }

    static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    /** Version qui laisse passer {@code null} (metrique non calculable). */
    static Double round(Double value, int decimals) {
        return value == null ? null : (Double) round(value.doubleValue(), decimals);
    }

    static double[] round(double[] values, int decimals) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = round(values[i], decimals);
        return out;
    }
}
