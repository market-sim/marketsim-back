package tn.esprit.marketsim.agent.engine;

import java.util.Arrays;

/** Statistiques utilisees par le Monte Carlo et le backtest. */
public final class Stats {

    private Stats() {
    }

    public static double mean(double[] x) {
        return Arrays.stream(x).average().orElse(0);
    }

    public static double std(double[] x) {
        if (x.length < 2) return 0;
        double m = mean(x);
        double s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return Math.sqrt(s / (x.length - 1));
    }

    /** Percentile (q dans [0,1]) par interpolation lineaire, sur un tableau DEJA trie. */
    public static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    /** Value at Risk : perte qui n'est depassee que dans (1 - level) des cas (positive = perte). */
    public static double valueAtRisk(double[] sortedReturns, double level) {
        return -percentile(sortedReturns, 1 - level);
    }

    /** Expected Shortfall (CVaR) : perte moyenne dans les pires (1 - level) des cas. */
    public static double expectedShortfall(double[] sortedReturns, double level) {
        int n = Math.max(1, (int) Math.floor(sortedReturns.length * (1 - level)));
        double s = 0;
        for (int i = 0; i < n; i++) s += sortedReturns[i];
        return -s / n;
    }

    /** Perte maximale depuis un plus haut (valeur negative, ex : -0.18 = -18 %). */
    public static double maxDrawdown(double[] equity) {
        double peak = equity[0];
        double worst = 0;
        for (double v : equity) {
            peak = Math.max(peak, v);
            worst = Math.min(worst, v / peak - 1);
        }
        return worst;
    }

    /** Ratio de Sharpe annualise (taux sans risque = 0) d'une courbe de valeur journaliere. */
    public static double sharpe(double[] equity) {
        if (equity.length < 3) return 0;
        double[] r = new double[equity.length - 1];
        for (int i = 1; i < equity.length; i++) r[i - 1] = equity[i] / equity[i - 1] - 1;
        double sd = std(r);
        return sd < 1e-12 ? 0 : mean(r) / sd * Math.sqrt(PathGenerator.TRADING_DAYS);
    }

    public static double[] sortedCopy(double[] x) {
        double[] c = x.clone();
        Arrays.sort(c);
        return c;
    }

    // ===================== Tests statistiques =====================

    /** p-value bilaterale du test t de Student : P(|T| >= |t|) avec df degres de liberte. */
    public static double studentTwoSidedP(double t, double df) {
        if (Double.isNaN(t)) return 1;
        if (Double.isInfinite(t)) return 0;
        return regularizedIncompleteBeta(df / 2, 0.5, df / (df + t * t));
    }

    /** Fonction de repartition de la loi normale centree reduite. */
    public static double normalCdf(double z) {
        return 0.5 * erfc(-z / Math.sqrt(2));
    }

    /**
     * Correction de Holm-Bonferroni pour comparaisons multiples : controle le risque
     * de declarer a tort au moins une difference significative (FWER).
     */
    public static double[] holm(double[] pValues) {
        int m = pValues.length;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(pValues[a], pValues[b]));
        double[] adjusted = new double[m];
        double running = 0;
        for (int rank = 0; rank < m; rank++) {
            int i = order[rank];
            running = Math.max(running, Math.min(1, (m - rank) * pValues[i]));
            adjusted[i] = running;
        }
        return adjusted;
    }

    /** Fonction beta incomplete regularisee I_x(a, b) (fraction continue de Lentz, Numerical Recipes). */
    static double regularizedIncompleteBeta(double a, double b, double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double lnFront = logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log(1 - x);
        double front = Math.exp(lnFront);
        if (x < (a + 1) / (a + b + 2)) {
            return front * betaContinuedFraction(a, b, x) / a;
        }
        return 1 - front * betaContinuedFraction(b, a, 1 - x) / b;
    }

    private static double betaContinuedFraction(double a, double b, double x) {
        final double tiny = 1e-300;
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1;
        double d = 1 - qab * x / qap;
        if (Math.abs(d) < tiny) d = tiny;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < 1e-12) break;
        }
        return h;
    }

    /** ln(n!) */
    public static double logFactorial(int n) {
        return n < 2 ? 0 : logGamma(n + 1.0);
    }

    /** ln Gamma(x) par l'approximation de Lanczos (precision ~1e-15). */
    public static double logGamma(double x) {
        double[] g = {57.1562356658629235, -59.5979603554754912, 14.1360979747417471, -0.491913816097620199,
                0.339946499848118887e-4, 0.465236289270485756e-4, -0.983744753048795646e-4,
                0.158088703224912494e-3, -0.210264441724104883e-3, 0.217439618115212643e-3,
                -0.164318106536763890e-3, 0.844182239838527433e-4, -0.261908384015814087e-4,
                0.368991826595316234e-5};
        double y = x;
        double tmp = x + 5.24218750000000000;
        tmp = (x + 0.5) * Math.log(tmp) - tmp;
        double ser = 0.999999999999997092;
        for (double c : g) ser += c / ++y;
        return tmp + Math.log(2.5066282746310005 * ser / x);
    }

    /** Fonction d'erreur complementaire (Numerical Recipes erfcc, erreur relative < 1.2e-7). */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }
}
