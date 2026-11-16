package tn.esprit.marketsim.market;

/**
 * Calculs purs d'un pas de prix (sans base de donnees ni horloge : testables).
 * <ul>
 *   <li>{@link #gbm} : mouvement brownien geometrique sans derive, S' = S·exp(σ√dt·Z − σ²dt/2) (esperance
 *       inchangee), avec Z borne a ±{@value #MAX_Z} pour eviter les sauts absurdes.</li>
 *   <li>{@link #deviation} : ecart log au cours reel, x' = (1−θ)·x + σ√dt·Z, borne a ±maxDeviation.</li>
 * </ul>
 * Un prix n'est jamais ≤ 0 : il est borne par {@link #MIN_PRICE}.
 */
public final class PriceSteps {

    /** Choc maximal par pas, en ecarts-types. */
    public static final double MAX_Z = 4.0;
    /** Plus petit prix autorise. */
    public static final double MIN_PRICE = 1e-6;
    /** Secondes dans une annee de marche (252 seances de 24 h : le moteur tourne en continu). */
    public static final double SECONDS_PER_YEAR = 252.0 * 24 * 3600;

    private PriceSteps() {
    }

    /** Pas de temps en annees pour un intervalle (ms) et une acceleration donnes. */
    public static double dtYears(long intervalMs, double speed) {
        return Math.max(0, intervalMs) / 1000.0 * Math.max(0, speed) / SECONDS_PER_YEAR;
    }

    public static double clampZ(double z) {
        if (Double.isNaN(z)) return 0;
        return Math.max(-MAX_Z, Math.min(MAX_Z, z));
    }

    /** Un pas de marche aleatoire libre. */
    public static double gbm(double price, double sigmaAnnual, double dtYears, double z) {
        double p = Math.max(MIN_PRICE, price);
        double s = Math.max(0, sigmaAnnual) * Math.sqrt(Math.max(0, dtYears));
        double next = p * Math.exp(s * clampZ(z) - 0.5 * s * s);
        return Double.isFinite(next) ? Math.max(MIN_PRICE, next) : p;
    }

    /** Nouvel ecart log au cours reel (rappel vers 0 puis choc), borne a ±maxDeviation. */
    public static double deviation(double x, double sigmaAnnual, double dtYears, double reversion, double maxDeviation,
                                   double z) {
        double theta = Math.max(0, Math.min(1, reversion));
        double s = Math.max(0, sigmaAnnual) * Math.sqrt(Math.max(0, dtYears));
        double next = (1 - theta) * x + s * clampZ(z);
        double bound = Math.abs(maxDeviation);
        if (!Double.isFinite(next)) return 0;
        return Math.max(-bound, Math.min(bound, next));
    }

    /** Prix ancre : cours reel × exp(ecart). */
    public static double anchored(double anchor, double deviation) {
        double next = Math.max(MIN_PRICE, anchor) * Math.exp(deviation);
        return Double.isFinite(next) ? Math.max(MIN_PRICE, next) : Math.max(MIN_PRICE, anchor);
    }

    /** Variation en % (2 decimales) depuis le prix d'ouverture du jour. */
    public static double changePct(double price, double open) {
        if (open <= 0) return 0;
        return Math.round((price / open - 1) * 10_000) / 100.0;
    }
}
