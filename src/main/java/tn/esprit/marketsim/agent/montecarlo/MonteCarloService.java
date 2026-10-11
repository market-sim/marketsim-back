package tn.esprit.marketsim.agent.montecarlo;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.ConvergencePoint;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.FanPoint;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.HistogramBin;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.MonteCarloRequest;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.MonteCarloResult;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.RiskSummary;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.CrashScenario;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PathGenerator.Shocks;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.agent.engine.TradingPolicy;
import tn.esprit.marketsim.market.Asset;

/**
 * Simulation Monte Carlo : l'agent trade sur N futurs possibles de l'actif.
 *
 * <ol>
 *   <li>Tirage de N trajectoires (GBM ou Merton jump-diffusion), en parallele, avec variables
 *       antithetiques optionnelles ;</li>
 *   <li>backtest de l'agent et du buy &amp; hold sur chaque trajectoire ;</li>
 *   <li>agregation : distribution des rendements, VaR / CVaR, drawdowns, Sharpe,
 *       cone de projection (percentiles jour par jour), histogramme, convergence.</li>
 * </ol>
 * Chaque trajectoire a sa propre graine derivee du seed global : le resultat est
 * <b>reproductible</b> quel que soit l'ordre d'execution des threads.
 */
@Service
@RequiredArgsConstructor
public class MonteCarloService {

    /** Jours d'historique simule avant le debut (necessaires aux indicateurs). */
    static final int WARMUP = 60;
    static final int HISTOGRAM_BINS = 30;
    static final int CONVERGENCE_POINTS = 40;
    static final int SAMPLE_PATHS = 8;

    private final AgentLabSupport support;
    private final MonteCarloRunRepository runRepository;

    public MonteCarloResult run(AgentConfig agent, MonteCarloRequest req) {
        long started = System.currentTimeMillis();

        Asset asset = support.asset(req.symbol());
        AssetDynamics dynamics = support.dynamics(asset).withOverrides(req.mu(), req.sigma());
        TradingPolicy policy = support.policy(agent);
        PriceModel model = req.model() == null ? PriceModel.GBM : req.model();
        int requested = clamp(req.paths(), 1000, 100, 5000);
        int horizon = clamp(req.horizonDays(), 252, 20, 504);
        boolean antithetic = req.antithetic() == null || req.antithetic();
        long seed = req.seed() != null ? req.seed() : ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
        double capital = req.capital() != null && req.capital() > 0 ? req.capital() : 10_000;
        double s0 = asset.getPrice().doubleValue();
        double risk = agent.risk();
        CrashScenario crash = crashScenario(req, horizon);

        // ---- 1. Simulation (parallele) ----
        int groups = antithetic ? (requested + 1) / 2 : requested;
        int n = antithetic ? groups * 2 : requested;
        Backtester.Result[] results = new Backtester.Result[n];
        double[][] priceDays = new double[n][];

        IntStream.range(0, groups).parallel().forEach(g -> {
            SplittableRandom rng = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + g);
            Shocks drawn = PathGenerator.drawShocks(WARMUP + horizon, dynamics, model, rng);
            Shocks shocks = crash == null ? drawn : crash.apply(drawn, WARMUP);
            int copies = antithetic ? 2 : 1;
            for (int c = 0; c < copies; c++) {
                // l'historique demarre plus bas/haut pour que le jour 0 de la simulation soit au prix actuel
                double[] path = PathGenerator.build(1.0, shocks, dynamics, model, c == 1);
                double scale = s0 / path[WARMUP];
                for (int i = 0; i < path.length; i++) path[i] *= scale;
                int index = g * copies + c;
                results[index] = Backtester.run(path, WARMUP, policy, risk, dynamics.sigma(), capital);
                priceDays[index] = path;
            }
        });

        // ---- 2. Agregation ----
        double[] agentReturns = new double[n];
        double[] benchReturns = new double[n];
        double[] agentDd = new double[n];
        double[] benchDd = new double[n];
        double[] agentSharpe = new double[n];
        double[] benchSharpe = new double[n];
        double trades = 0;
        double wins = 0;
        double exposure = 0;
        int beats = 0;
        double[][] benchEquity = new double[n][];
        for (int i = 0; i < n; i++) {
            Backtester.Result r = results[i];
            agentReturns[i] = r.totalReturn();
            benchReturns[i] = r.benchmarkReturn();
            agentDd[i] = r.maxDrawdown();
            agentSharpe[i] = r.sharpe();
            trades += r.trades();
            wins += r.wins();
            exposure += r.exposure();
            if (r.totalReturn() > r.benchmarkReturn()) beats++;

            double[] be = new double[horizon + 1];
            for (int t = 0; t <= horizon; t++) be[t] = priceDays[i][WARMUP + t] / priceDays[i][WARMUP];
            benchEquity[i] = be;
            benchDd[i] = Stats.maxDrawdown(be);
            benchSharpe[i] = Stats.sharpe(be);
        }

        RiskSummary agentSummary = summary(agentReturns, agentDd, agentSharpe);
        RiskSummary benchSummary = summary(benchReturns, benchDd, benchSharpe);

        List<FanPoint> fan = fan(results, benchEquity, horizon, capital);
        List<HistogramBin> histogram = histogram(agentReturns, benchReturns);
        List<ConvergencePoint> convergence = convergence(agentReturns);
        List<double[]> samples = new ArrayList<>();
        for (int i = 0; i < Math.min(SAMPLE_PATHS, n); i++) {
            double[] eq = results[i].equity();
            double[] normalized = new double[eq.length];
            for (int t = 0; t < eq.length; t++) normalized[t] = round2(eq[t] / capital * 100);
            samples.add(normalized);
        }

        long elapsed = System.currentTimeMillis() - started;

        // ---- 3. Historisation du resume ----
        MonteCarloRun run = new MonteCarloRun();
        run.setAgent(agent);
        run.setSymbol(asset.getSymbol());
        run.setStrategy(agent.getStrategy());
        run.setModel(model);
        run.setPaths(n);
        run.setHorizonDays(horizon);
        run.setSeed(seed);
        run.setAntithetic(antithetic);
        run.setMu(req.mu());
        run.setSigma(req.sigma());
        run.setMeanReturn(agentSummary.meanReturn());
        run.setVar95(agentSummary.var95());
        run.setCvar95(agentSummary.cvar95());
        run.setProbProfit(agentSummary.probProfit());
        run.setProbBeatBenchmark((double) beats / n);
        run.setBenchmarkMeanReturn(benchSummary.meanReturn());
        run.setMeanSharpe(agentSummary.meanSharpe());
        run.setMeanMaxDrawdown(agentSummary.meanMaxDrawdown());
        run.setWinRate(trades == 0 ? 0 : wins / trades);
        if (crash != null) {
            run.setCrashPct(crash.dropPct() * 100);
            run.setCrashDay(crash.day());
            run.setVolMultiplier(crash.volMultiplier());
            run.setVolDays(crash.volDays());
        }
        run.setElapsedMs(elapsed);
        runRepository.save(run);

        return new MonteCarloResult(run.getId(), asset.getSymbol(), model, n, horizon, seed, antithetic,
                dynamics.mu(), dynamics.sigma(), s0, elapsed, agentSummary, benchSummary,
                (double) beats / n, trades / n, trades == 0 ? 0 : wins / trades, exposure / n,
                fan, histogram, convergence, samples,
                crash == null ? null : crash.dropPct() * 100, crash == null ? null : crash.day(),
                crash == null ? null : crash.volMultiplier(), crash == null ? null : crash.volDays());
    }

    /** Scenario de krach demande (null si aucun). */
    static CrashScenario crashScenario(MonteCarloRequest req, int horizon) {
        if (req.crashPct() == null || req.crashPct() <= 0) return null;
        int day = req.crashDay() == null ? horizon / 4 : Math.max(1, Math.min(horizon, req.crashDay()));
        double vol = req.volMultiplier() == null ? 2.0 : req.volMultiplier();
        int volDays = req.volDays() == null ? 20 : Math.max(0, Math.min(horizon, req.volDays()));
        return new CrashScenario(req.crashPct() / 100, day, vol, volDays);
    }

    public List<MonteCarloRun> history(AgentConfig agent) {
        return runRepository.findTop20ByAgentIdOrderByCreatedAtDescIdDesc(agent.getId());
    }

    // ---------------------------------------------------------------------------------------

    private static RiskSummary summary(double[] returns, double[] drawdowns, double[] sharpes) {
        double[] sorted = Stats.sortedCopy(returns);
        long profitable = java.util.Arrays.stream(returns).filter(r -> r > 0).count();
        return new RiskSummary(
                Stats.mean(returns),
                Stats.percentile(sorted, 0.5),
                Stats.std(returns),
                Stats.valueAtRisk(sorted, 0.95),
                Stats.expectedShortfall(sorted, 0.95),
                Stats.valueAtRisk(sorted, 0.99),
                (double) profitable / returns.length,
                Stats.mean(drawdowns),
                java.util.Arrays.stream(drawdowns).min().orElse(0),
                Stats.mean(sharpes));
    }

    /** Percentiles 5/25/50/75/95 de la valeur du portefeuille (base 100) pour chaque jour. */
    private static List<FanPoint> fan(Backtester.Result[] results, double[][] benchEquity, int horizon, double capital) {
        int n = results.length;
        List<FanPoint> fan = new ArrayList<>(horizon + 1);
        double[] column = new double[n];
        double[] benchColumn = new double[n];
        for (int t = 0; t <= horizon; t++) {
            for (int i = 0; i < n; i++) {
                column[i] = results[i].equity()[t] / capital * 100;
                benchColumn[i] = benchEquity[i][t] * 100;
            }
            double[] s = Stats.sortedCopy(column);
            double[] b = Stats.sortedCopy(benchColumn);
            fan.add(new FanPoint(t, round2(Stats.percentile(s, 0.05)), round2(Stats.percentile(s, 0.25)),
                    round2(Stats.percentile(s, 0.5)), round2(Stats.percentile(s, 0.75)),
                    round2(Stats.percentile(s, 0.95)), round2(Stats.percentile(b, 0.5))));
        }
        return fan;
    }

    /** Histogramme commun (memes classes) des rendements de l'agent et du buy & hold. */
    private static List<HistogramBin> histogram(double[] agent, double[] bench) {
        double[] sa = Stats.sortedCopy(agent);
        double[] sb = Stats.sortedCopy(bench);
        // on coupe les 0,5 % extremes pour que quelques trajectoires folles n'ecrasent pas le graphique
        double min = Math.min(Stats.percentile(sa, 0.005), Stats.percentile(sb, 0.005));
        double max = Math.max(Stats.percentile(sa, 0.995), Stats.percentile(sb, 0.995));
        if (max - min < 1e-9) {
            max = min + 1e-3;
        }
        double width = (max - min) / HISTOGRAM_BINS;
        int[] ca = new int[HISTOGRAM_BINS];
        int[] cb = new int[HISTOGRAM_BINS];
        for (double v : agent) ca[bin(v, min, width)]++;
        for (double v : bench) cb[bin(v, min, width)]++;
        List<HistogramBin> bins = new ArrayList<>();
        for (int i = 0; i < HISTOGRAM_BINS; i++) {
            bins.add(new HistogramBin(min + i * width, min + (i + 1) * width, ca[i], cb[i]));
        }
        return bins;
    }

    private static int bin(double v, double min, double width) {
        return Math.max(0, Math.min(HISTOGRAM_BINS - 1, (int) ((v - min) / width)));
    }

    /** Moyenne cumulee +/- 1,96 erreur-type : montre que l'estimateur Monte Carlo converge. */
    private static List<ConvergencePoint> convergence(double[] returns) {
        List<ConvergencePoint> points = new ArrayList<>();
        int n = returns.length;
        double sum = 0;
        double sumSq = 0;
        int next = 1;
        int step = Math.max(1, n / CONVERGENCE_POINTS);
        for (int i = 0; i < n; i++) {
            sum += returns[i];
            sumSq += returns[i] * returns[i];
            int k = i + 1;
            if (k >= 10 && (k == next || k == n)) {
                double mean = sum / k;
                double variance = Math.max(0, (sumSq - k * mean * mean) / (k - 1));
                double halfWidth = 1.96 * Math.sqrt(variance / k);
                points.add(new ConvergencePoint(k, mean, mean - halfWidth, mean + halfWidth));
            }
            if (k >= next) next += step;
        }
        return points;
    }

    private static int clamp(Integer value, int def, int min, int max) {
        int v = value == null ? def : value;
        return Math.max(min, Math.min(max, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
