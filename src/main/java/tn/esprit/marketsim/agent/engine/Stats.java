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
}
