package tn.esprit.marketsim.user.anomaly;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Modele d'anomalie versionne : distance de Mahalanobis sur un gaussien multivarie
 * estime a partir de l'historique de connexions (pas un score a regles fixes seules).
 */
public final class LoginAnomalyModel {

    public static final String VERSION = "login-mahalanobis-v1";
    public static final int MIN_HISTORY = 12;

    private final double[] mean;
    private final double[][] invCov;
    private final double maxTrainDistance;

    private LoginAnomalyModel(double[] mean, double[][] invCov, double maxTrainDistance) {
        this.mean = mean;
        this.invCov = invCov;
        this.maxTrainDistance = Math.max(maxTrainDistance, 1e-6);
    }

    public String version() {
        return VERSION;
    }

    public static LoginAnomalyModel train(List<LoginFeatureVector> history) {
        if (history == null || history.size() < MIN_HISTORY) {
            throw new IllegalArgumentException("INSUFFICIENT_HISTORY");
        }
        int n = history.size();
        int d = LoginFeatureVector.DIMENSION;
        double[] mean = new double[d];
        for (LoginFeatureVector v : history) {
            double[] x = v.toArray();
            for (int i = 0; i < d; i++) {
                mean[i] += x[i];
            }
        }
        for (int i = 0; i < d; i++) {
            mean[i] /= n;
        }

        double[][] cov = new double[d][d];
        for (LoginFeatureVector v : history) {
            double[] x = v.toArray();
            for (int i = 0; i < d; i++) {
                for (int j = 0; j < d; j++) {
                    cov[i][j] += (x[i] - mean[i]) * (x[j] - mean[j]);
                }
            }
        }
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                cov[i][j] /= Math.max(1, n - 1);
            }
            cov[i][i] += 1e-3; // regularisation
        }

        double[][] inv = invert(cov);
        double maxDist = 0;
        for (LoginFeatureVector v : history) {
            maxDist = Math.max(maxDist, mahalanobis(v.toArray(), mean, inv));
        }
        return new LoginAnomalyModel(mean, inv, maxDist);
    }

    /** Score 0–100 : plus la distance de Mahalanobis est grande, plus le risque est eleve. */
    public double score(LoginFeatureVector current) {
        double dist = mahalanobis(current.toArray(), mean, invCov);
        double ratio = dist / maxTrainDistance;
        double mapped = 100.0 * (1.0 - Math.exp(-ratio));
        return Math.max(0, Math.min(100, mapped));
    }

    public List<String> explain(LoginFeatureVector current, double score) {
        List<String> codes = new ArrayList<>();
        double[] x = current.toArray();
        if (Math.abs(x[0] - mean[0]) + Math.abs(x[1] - mean[1]) > 0.85) {
            codes.add("UNUSUAL_HOUR");
        }
        if (Math.abs(x[2] - mean[2]) > 0.35) {
            codes.add("UNUSUAL_WEEKDAY");
        }
        if (x[4] > mean[4] + 0.25) {
            codes.add("ELEVATED_RECENT_FAILURES");
        }
        if (x[5] > 0.5 && mean[5] < 0.35) {
            codes.add("FAILURE_VS_BASELINE");
        }
        if (x[6] > mean[6] + 0.4) {
            codes.add("UNFAMILIAR_DEVICE");
        }
        if (current.minutesSinceLastLogin() != null && current.minutesSinceLastLogin() < 2 && score >= 40) {
            codes.add("RAPID_RETRY");
        }
        if (codes.isEmpty()) {
            codes.add(score >= 70 ? "MULTIVARIATE_OUTLIER" : "WITHIN_BASELINE");
        }
        return codes;
    }

    private static double mahalanobis(double[] x, double[] mean, double[][] inv) {
        int d = x.length;
        double[] diff = new double[d];
        for (int i = 0; i < d; i++) {
            diff[i] = x[i] - mean[i];
        }
        double sum = 0;
        for (int i = 0; i < d; i++) {
            double acc = 0;
            for (int j = 0; j < d; j++) {
                acc += inv[i][j] * diff[j];
            }
            sum += diff[i] * acc;
        }
        return Math.sqrt(Math.max(0, sum));
    }

    /** Inversion Gauss-Jordan pour matrices petites (d=7). */
    private static double[][] invert(double[][] matrix) {
        int n = matrix.length;
        double[][] a = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, a[i], 0, n);
            a[i][i + n] = 1.0;
        }
        for (int i = 0; i < n; i++) {
            int pivot = i;
            for (int r = i + 1; r < n; r++) {
                if (Math.abs(a[r][i]) > Math.abs(a[pivot][i])) {
                    pivot = r;
                }
            }
            double[] tmp = a[i];
            a[i] = a[pivot];
            a[pivot] = tmp;
            double div = a[i][i];
            if (Math.abs(div) < 1e-12) {
                div = 1e-12;
            }
            for (int c = 0; c < 2 * n; c++) {
                a[i][c] /= div;
            }
            for (int r = 0; r < n; r++) {
                if (r == i) {
                    continue;
                }
                double factor = a[r][i];
                for (int c = 0; c < 2 * n; c++) {
                    a[r][c] -= factor * a[i][c];
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], n, inv[i], 0, n);
        }
        return inv;
    }

    public static String normalizeBrowserFamily(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT).trim();
        if (ua.equals("chrome") || ua.equals("firefox") || ua.equals("safari")
                || ua.equals("edge") || ua.equals("other") || ua.equals("unknown")) {
            return ua;
        }
        if (ua.contains("edg/")) {
            return "edge";
        }
        if (ua.contains("chrome/") && !ua.contains("edg/")) {
            return "chrome";
        }
        if (ua.contains("firefox/")) {
            return "firefox";
        }
        if (ua.contains("safari/") && !ua.contains("chrome/")) {
            return "safari";
        }
        return "other";
    }
}
