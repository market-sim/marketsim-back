package tn.esprit.marketsim.agent.realdata;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.TradingPolicy;

/**
 * Backtest <b>walk-forward</b> sur historique reel : l'agent n'est jamais evalue sur des donnees qu'il a vues.
 *
 * <pre>
 *   |---- entrainement (trainDays) ----|-- test (testDays) --|
 *                    |---- entrainement ----|-- test --|            fenetre suivante : on avance de testDays
 * </pre>
 * Pour chaque fenetre : (1) la politique est construite uniquement avec le passe (barres strictement avant le test),
 * (2) elle trade la fenetre de test, qui sert de resultat hors echantillon. Les fenetres de test se touchent
 * (la derniere barre d'une fenetre est la premiere de la suivante) : mises bout a bout elles donnent une
 * courbe continue, comparee au buy &amp; hold sur exactement la meme periode.
 */
public final class WalkForward {

    /** Barres d'historique laissees aux indicateurs avant le premier jour de trading. */
    public static final int WARMUP = 60;
    public static final double CAPITAL = 10_000;

    private WalkForward() {
    }

    /** Fournit la politique d'une fenetre a partir du seul passe (entrainement pour un agent qui apprend). */
    @FunctionalInterface
    public interface PolicySupplier {
        TradingPolicy forWindow(double[] trainPrices, double sigma, long seed);
    }

    public record Window(int index, LocalDate trainFrom, LocalDate trainTo, LocalDate testFrom, LocalDate testTo,
                         double sigma, double agentReturn, double benchmarkReturn, double maxDrawdown, int trades,
                         double exposure, double inSampleReturn, double inSampleBenchmark) {
        public double excess() {
            return agentReturn - benchmarkReturn;
        }
    }

    /**
     * @param agentCurve     valeur du portefeuille de l'agent, fenetres bout a bout (capital initial {@link #CAPITAL})
     * @param benchmarkCurve valeur du buy &amp; hold sur la meme periode
     */
    public record Result(List<Window> windows, LocalDate[] dates, double[] agentCurve, double[] benchmarkCurve) {
    }

    /** Nombre de fenetres de test qui tiennent dans {@code bars} barres. */
    public static int windowCount(int bars, int trainDays, int testDays) {
        return Math.max(0, (bars - 1 - trainDays) / testDays);
    }

    public static Result run(double[] prices, LocalDate[] dates, int trainDays, int testDays, double risk,
                             PolicySupplier supplier, long seed) {
        if (trainDays <= WARMUP + 30) {
            throw new IllegalArgumentException("Fenêtre d'entraînement trop courte");
        }
        int count = windowCount(prices.length, trainDays, testDays);
        if (count < 1) {
            throw new IllegalArgumentException("Historique trop court pour une fenêtre de test");
        }

        List<Window> windows = new ArrayList<>();
        int bars = count * testDays + 1;
        LocalDate[] curveDates = new LocalDate[bars];
        double[] agentCurve = new double[bars];
        double[] benchCurve = new double[bars];
        double cash = CAPITAL;
        double benchCash = CAPITAL;
        agentCurve[0] = CAPITAL;
        benchCurve[0] = CAPITAL;
        curveDates[0] = dates[trainDays];

        for (int k = 0; k < count; k++) {
            int ts = trainDays + k * testDays;      // premiere barre de test
            int te = ts + testDays;                 // derniere barre de test (incluse)
            double[] train = Arrays.copyOfRange(prices, ts - trainDays, ts);   // strictement le passe
            double sigma = annualVolatility(train);
            TradingPolicy policy = supplier.forWindow(train, sigma, seed + 7_919L * (k + 1));

            double[] slice = Arrays.copyOfRange(prices, ts - WARMUP, te + 1);
            Backtester.Result out = Backtester.run(slice, WARMUP, policy, risk, sigma, cash);
            Backtester.Result in = Backtester.run(train, WARMUP, policy, risk, sigma, CAPITAL);

            double[] eq = out.equity();
            for (int j = 1; j <= testDays; j++) {
                int at = k * testDays + j;
                curveDates[at] = dates[ts + j];
                agentCurve[at] = eq[j];
                benchCurve[at] = benchCash * prices[ts + j] / prices[ts];
            }
            cash = eq[testDays];
            benchCash = benchCash * prices[te] / prices[ts];

            windows.add(new Window(k + 1, dates[ts - trainDays], dates[ts - 1], dates[ts], dates[te], sigma,
                    out.totalReturn(), out.benchmarkReturn(), out.maxDrawdown(), out.trades(), out.exposure(),
                    in.totalReturn(), in.benchmarkReturn()));
        }
        return new Result(windows, curveDates, agentCurve, benchCurve);
    }

    /** Volatilite annualisee des rendements logarithmiques (plancher 2 % pour eviter une normalisation degeneree). */
    public static double annualVolatility(double[] prices) {
        int n = prices.length - 1;
        double mean = 0;
        for (int i = 1; i < prices.length; i++) mean += Math.log(prices[i] / prices[i - 1]);
        mean /= n;
        double s = 0;
        for (int i = 1; i < prices.length; i++) {
            double r = Math.log(prices[i] / prices[i - 1]) - mean;
            s += r * r;
        }
        return Math.max(0.02, Math.sqrt(s / (n - 1)) * Math.sqrt(PathGenerator.TRADING_DAYS));
    }
}
