package tn.esprit.marketsim.user.level;

/** Signaux d'activite du compte, normalises entre 0 et 1. Aucun secret. */
public record UserLevelFeatures(
        double loginNorm,
        double profileNorm,
        double orderNorm,
        double closedRate,
        double protectionRate,
        double symbolDiversity,
        double sideBalance,
        double ageNorm,
        int successfulLogins) {

    public double[] toArray() {
        return new double[] {
                loginNorm,
                profileNorm,
                orderNorm,
                closedRate,
                protectionRate,
                symbolDiversity,
                sideBalance,
                ageNorm
        };
    }

    public static final int DIMENSION = 8;
}
