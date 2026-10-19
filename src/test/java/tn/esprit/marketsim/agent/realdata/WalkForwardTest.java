package tn.esprit.marketsim.agent.realdata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.engine.QLearningPolicy;
import tn.esprit.marketsim.agent.engine.TradingPolicy;
import tn.esprit.marketsim.agent.engine.TradingPolicy.PolicyDecision;
import tn.esprit.marketsim.agent.training.QLearningTrainer;

/** Backtest walk-forward : pas de fuite du futur, courbes continues, accord avec le buy &amp; hold. */
class WalkForwardTest {

    private static final AssetDynamics STOCK = new AssetDynamics(0.08, 0.30, 3, -0.04, 0.06);
    private static final TradingPolicy HOLD = (f, pos) -> new PolicyDecision(AgentAction.HOLD, 0, 1, List.of(), null);
    private static final TradingPolicy ALWAYS_BUY = (f, pos) -> new PolicyDecision(AgentAction.BUY, 1, 1, List.of(), null);

    private static double[] prices(int n, long seed) {
        return PathGenerator.generate(100, n - 1, STOCK, PriceModel.REGIME_SWITCHING, new SplittableRandom(seed));
    }

    private static LocalDate[] dates(int n) {
        LocalDate[] d = new LocalDate[n];
        LocalDate day = LocalDate.of(2015, 1, 1);
        for (int i = 0; i < n; i++) {
            d[i] = day;
            day = day.plusDays(1);
        }
        return d;
    }

    @Test
    void windowCountMatchesTheLayout() {
        assertEquals(0, WalkForward.windowCount(600, 504, 126));
        assertEquals(1, WalkForward.windowCount(631, 504, 126));   // 504 + 126 + 1 barres
        assertEquals(16, WalkForward.windowCount(2521, 504, 126));
    }

    @Test
    void trainingNeverSeesATestBar() {
        int n = 1400;
        double[] p = prices(n, 3);
        LocalDate[] d = dates(n);
        List<double[]> seen = new ArrayList<>();
        WalkForward.Result r = WalkForward.run(p, d, 504, 126, 0.5, (train, sigma, seed) -> {
            seen.add(train.clone());
            return HOLD;
        }, 1);

        assertEquals(r.windows().size(), seen.size());
        for (int k = 0; k < seen.size(); k++) {
            int testStart = 504 + k * 126;
            double[] train = seen.get(k);
            assertEquals(504, train.length);
            // la derniere barre vue a l'entrainement est la veille du premier jour de test
            assertEquals(p[testStart - 1], train[train.length - 1], 0.0);
            assertEquals(d[testStart - 1], r.windows().get(k).trainTo());
            assertEquals(d[testStart], r.windows().get(k).testFrom());
            assertTrue(r.windows().get(k).trainTo().isBefore(r.windows().get(k).testFrom()));
        }
    }

    @Test
    void changingFutureBarsNeverChangesAnEarlierWindow() {
        int n = 1100;
        double[] p = prices(n, 11);
        double[] altered = p.clone();
        for (int i = 900; i < n; i++) altered[i] *= 1.7;   // le futur change, pas le passe
        LocalDate[] d = dates(n);
        WalkForward.PolicySupplier supplier = (train, sigma, seed) ->
                new QLearningPolicy(QLearningTrainer.trainOnSeries(train, sigma, 0.5, 60, seed));

        WalkForward.Result a = WalkForward.run(p, d, 504, 126, 0.5, supplier, 5);
        WalkForward.Result b = WalkForward.run(altered, d, 504, 126, 0.5, supplier, 5);
        // fenetre 1 : test sur les barres 504..630, donc independante des barres >= 900
        assertEquals(a.windows().get(0).agentReturn(), b.windows().get(0).agentReturn(), 0.0);
        assertEquals(a.windows().get(0).inSampleReturn(), b.windows().get(0).inSampleReturn(), 0.0);
    }

    @Test
    void holdingPolicyStaysFlatAndBenchmarkMatchesBuyAndHold() {
        int n = 1300;
        double[] p = prices(n, 21);
        WalkForward.Result r = WalkForward.run(p, dates(n), 504, 126, 0.5, (t, s, seed) -> HOLD, 1);

        for (double v : r.agentCurve()) assertEquals(WalkForward.CAPITAL, v, 1e-9);
        int last = r.windows().size() * 126;
        double first = p[504];
        double finalBar = p[504 + last];
        assertEquals(WalkForward.CAPITAL * finalBar / first, r.benchmarkCurve()[r.benchmarkCurve().length - 1], 1e-6,
                "le buy & hold se telescope sur toute la periode de test");
        assertEquals(0.0, r.windows().get(0).exposure(), 0.0);
    }

    @Test
    void curvesAreContinuousAcrossWindowBoundaries() {
        int n = 1300;
        double[] p = prices(n, 5);
        WalkForward.Result r = WalkForward.run(p, dates(n), 504, 126, 0.8, (t, s, seed) -> ALWAYS_BUY, 1);
        int bars = r.agentCurve().length;
        assertEquals(r.windows().size() * 126 + 1, bars);
        // pas de saut de plus de 30 % d'une barre a l'autre, y compris aux frontieres de fenetre
        for (int i = 1; i < bars; i++) {
            assertTrue(Math.abs(r.agentCurve()[i] / r.agentCurve()[i - 1] - 1) < 0.3, "saut a la barre " + i);
        }
        // la derniere barre d'une fenetre est la premiere de la suivante
        assertEquals(r.windows().get(0).testTo(), r.windows().get(1).testFrom());
    }

    @Test
    void alwaysBuyOnASteadyUptrendMakesMoneyAndIsReproducible() {
        int n = 1300;
        double[] p = new double[n];
        for (int i = 0; i < n; i++) p[i] = 100 * Math.exp(0.0006 * i + 0.002 * Math.sin(i));
        WalkForward.Result a = WalkForward.run(p, dates(n), 504, 126, 1.0, (t, s, seed) -> ALWAYS_BUY, 1);
        WalkForward.Result b = WalkForward.run(p, dates(n), 504, 126, 1.0, (t, s, seed) -> ALWAYS_BUY, 1);
        assertTrue(a.agentCurve()[a.agentCurve().length - 1] > WalkForward.CAPITAL * 1.05);
        assertArrayEquals(a.agentCurve(), b.agentCurve(), 0.0);
    }

    @Test
    void tooShortHistoryIsRejected() {
        double[] p = prices(400, 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> WalkForward.run(p, dates(400), 504, 126, 0.5, (t, s, seed) -> HOLD, 1));
    }

    @Test
    void realDataTrainingIsDeterministicForAGivenSeed() {
        double[] p = prices(504, 9);
        double sigma = WalkForward.annualVolatility(p);
        String a = QLearningTrainer.trainOnSeries(p, sigma, 0.5, 80, 42).serialize();
        String b = QLearningTrainer.trainOnSeries(p, sigma, 0.5, 80, 42).serialize();
        String c = QLearningTrainer.trainOnSeries(p, sigma, 0.5, 80, 43).serialize();
        assertEquals(a, b);
        assertTrue(!a.equals(c), "une autre graine explore autrement");
    }
}
