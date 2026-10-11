package tn.esprit.marketsim.agent.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentStrategy;

/** Tests unitaires du moteur du module 6 (pas besoin de base de donnees). */
class AgentEngineTest {

    private static final AssetDynamics STOCK = new AssetDynamics(0.08, 0.30, 3, -0.04, 0.06);

    @Test
    void gbmMatchesTheoreticalExpectation() {
        // E[S_T] = S0 * exp(mu * T) pour un GBM
        SplittableRandom rng = new SplittableRandom(42);
        int n = 20_000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double[] p = PathGenerator.generate(100, 252, STOCK, PriceModel.GBM, rng);
            sum += p[252];
        }
        double expected = 100 * Math.exp(0.08);
        assertEquals(expected, sum / n, expected * 0.01, "moyenne Monte Carlo a 1 % de la theorie");
    }

    @Test
    void jumpDiffusionKeepsSameExpectationButFatterLeftTail() {
        SplittableRandom rng = new SplittableRandom(7);
        int n = 20_000;
        double[] gbm = new double[n];
        double[] jump = new double[n];
        double sumJump = 0;
        for (int i = 0; i < n; i++) {
            gbm[i] = PathGenerator.generate(100, 252, STOCK, PriceModel.GBM, rng)[252] / 100 - 1;
            jump[i] = PathGenerator.generate(100, 252, STOCK, PriceModel.JUMP_DIFFUSION, rng)[252] / 100 - 1;
            sumJump += jump[i] + 1;
        }
        // drift compense : meme esperance que le GBM
        assertEquals(Math.exp(0.08), sumJump / n, 0.02);
        // mais des pertes extremes plus frequentes (queue gauche plus epaisse)
        double gbmP1 = Stats.percentile(Stats.sortedCopy(gbm), 0.01);
        double jumpP1 = Stats.percentile(Stats.sortedCopy(jump), 0.01);
        assertTrue(jumpP1 < gbmP1, "percentile 1 % plus bas avec sauts");
    }

    @Test
    void antitheticVariatesReduceEstimatorVariance() {
        // Variance de l'estimateur de E[S_T] sur 200 repetitions : antithetique < standard
        int reps = 200;
        int pathsPerRep = 200;
        double[] plain = new double[reps];
        double[] anti = new double[reps];
        SplittableRandom rng = new SplittableRandom(2024);
        for (int r = 0; r < reps; r++) {
            double s1 = 0;
            for (int i = 0; i < pathsPerRep; i++) {
                s1 += PathGenerator.generate(100, 50, STOCK, PriceModel.GBM, rng)[50];
            }
            plain[r] = s1 / pathsPerRep;
            double s2 = 0;
            for (int i = 0; i < pathsPerRep / 2; i++) {
                PathGenerator.Shocks shocks = PathGenerator.drawShocks(50, STOCK, PriceModel.GBM, rng);
                s2 += PathGenerator.build(100, shocks, STOCK, PriceModel.GBM, false)[50];
                s2 += PathGenerator.build(100, shocks, STOCK, PriceModel.GBM, true)[50];
            }
            anti[r] = s2 / pathsPerRep;
        }
        assertTrue(Stats.std(anti) < 0.5 * Stats.std(plain), "reduction de variance d'au moins 50 %");
    }

    @Test
    void valueAtRiskAndExpectedShortfall() {
        double[] returns = new double[100];
        for (int i = 0; i < 100; i++) returns[i] = (i - 50) / 100.0; // -0.50 .. +0.49
        double[] sorted = Stats.sortedCopy(returns);
        assertEquals(0.4505, Stats.valueAtRisk(sorted, 0.95), 1e-9);
        // moyenne des 5 pires : -0.50 -0.49 -0.48 -0.47 -0.46
        assertEquals(0.48, Stats.expectedShortfall(sorted, 0.95), 1e-9);
        assertTrue(Stats.expectedShortfall(sorted, 0.95) >= Stats.valueAtRisk(sorted, 0.95), "CVaR >= VaR");
    }

    @Test
    void maxDrawdown() {
        assertEquals(-0.5, Stats.maxDrawdown(new double[] {100, 120, 60, 90, 130}), 1e-9);
    }

    @Test
    void linearPolicyShapValuesSumToScore() {
        LinearPolicy policy = LinearPolicy.of(AgentStrategy.MOMENTUM, 0.5);
        MarketFeatures f = new MarketFeatures(0.8, 0.5, 0.3, -0.2, 0.4);
        TradingPolicy.PolicyDecision d = policy.decide(f, 0);
        double sum = d.contributions().stream().mapToDouble(TradingPolicy.Contribution::contribution).sum();
        assertEquals(d.score(), sum, 1e-12, "propriete d'additivite de SHAP");
        assertEquals(AgentAction.BUY, d.action());
    }

    @Test
    void neutralMarketGivesHold() {
        TradingPolicy.PolicyDecision d = LinearPolicy.of(AgentStrategy.MEAN_REVERSION, 0.5)
                .decide(new MarketFeatures(0, 0, 0, 0, 0), 0);
        assertEquals(AgentAction.HOLD, d.action());
        assertEquals(1.0, d.confidence(), 1e-12);
    }

    @Test
    void qTableRoundTrip() {
        QTable table = new QTable();
        table.update(5, 1, 3.0, 1.0);
        table.update(20, 2, -1.5, 1.0);
        QTable copy = QTable.parse(table.serialize());
        for (int s = 0; s < QTable.STATES; s++) {
            assertArrayEquals(table.values(s), copy.values(s), 1e-6);
        }
        int[] decoded = QTable.decode(QTable.state(2, 0, 1, 1));
        assertArrayEquals(new int[] {2, 0, 1, 1}, decoded);
    }

    @Test
    void crashScenarioDropsPriceExactlyOnCrashDay() {
        PathGenerator.Shocks shocks = PathGenerator.drawShocks(160, STOCK, PriceModel.GBM, new SplittableRandom(5));
        CrashScenario crash = new CrashScenario(0.20, 50, 1.0, 0);
        double[] normal = PathGenerator.build(100, shocks, STOCK, PriceModel.GBM, false);
        double[] crashed = PathGenerator.build(100, crash.apply(shocks, 60), STOCK, PriceModel.GBM, false);
        // avant le krach : identique ; a partir du jour 50 (index 110) : exactement -20 %
        assertEquals(normal[109], crashed[109], 1e-9);
        assertEquals(0.80, crashed[110] / normal[110], 1e-9);
        assertEquals(0.80, crashed[160] / normal[160], 1e-9);
    }

    @Test
    void backtesterNeverTradesWhenPolicyHolds() {
        double[] p = PathGenerator.generate(100, 200, STOCK, PriceModel.GBM, new SplittableRandom(1));
        Backtester.Result r = Backtester.run(p, 60, (f, pos) ->
                new TradingPolicy.PolicyDecision(AgentAction.HOLD, 0, 1, java.util.List.of(), null), 0.5, 0.3, 10_000);
        assertEquals(0, r.totalReturn(), 1e-12);
        assertEquals(0, r.trades());
    }
}
