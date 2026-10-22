package tn.esprit.marketsim.user.anomaly;

public enum LoginRiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static LoginRiskLevel fromScore(double score) {
        if (score < 40) {
            return LOW;
        }
        if (score < 70) {
            return MEDIUM;
        }
        return HIGH;
    }
}
