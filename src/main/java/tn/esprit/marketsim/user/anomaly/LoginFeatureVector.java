package tn.esprit.marketsim.user.anomaly;

/** Vecteur de features au moment de la tentative (aucun secret). */
public record LoginFeatureVector(
        double hourSin,
        double hourCos,
        double dayOfWeek,
        double minutesSinceLastLoginNorm,
        double recentFailureRate,
        double outcomeFailure,
        double deviceNovelty,
        String browserFamily,
        int recentFailureCount,
        Integer minutesSinceLastLogin) {

    public double[] toArray() {
        return new double[] {
                hourSin,
                hourCos,
                dayOfWeek,
                minutesSinceLastLoginNorm,
                recentFailureRate,
                outcomeFailure,
                deviceNovelty
        };
    }

    public static final int DIMENSION = 7;
}
