package tn.esprit.marketsim.agent.tournament;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentConfigService;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.dto.TournamentDtos.Competitor;
import tn.esprit.marketsim.agent.dto.TournamentDtos.EquityPoint;
import tn.esprit.marketsim.agent.dto.TournamentDtos.PairwiseTest;
import tn.esprit.marketsim.agent.dto.TournamentDtos.TournamentRequest;
import tn.esprit.marketsim.agent.dto.TournamentDtos.TournamentResult;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PathGenerator.Shocks;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.agent.engine.TradingPolicy;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.user.User;

/**
 * Tournoi d'agents avec inference statistique.
 *
 * <ol>
 *   <li><b>Nombres aleatoires communs</b> : tous les concurrents (agents + buy &amp; hold) tradent exactement
 *       les memes N trajectoires. Les differences de performance ne viennent donc pas de la chance
 *       d'avoir eu de "meilleurs" marches, et la variance de la comparaison est fortement reduite.</li>
 *   <li>Classement par ratio de Sharpe moyen (performance ajustee du risque).</li>
 *   <li>Pour chaque paire, sur les differences par trajectoire : test t apparie, intervalle de confiance
 *       bootstrap a 95 %, p-value bootstrap, test des signes (non parametrique), taille d'effet de Cohen.</li>
 *   <li>Correction de Holm-Bonferroni : avec k concurrents on fait k(k-1)/2 tests, sans correction on
 *       trouverait des "differences" par pur hasard.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class TournamentService {

    static final int WARMUP = 60;
    static final int MAX_AGENTS = 6;
    static final int BOOTSTRAP_SAMPLES = 2000;
    static final int EQUITY_POINTS = 60;
    static final double ALPHA = 0.05;
    static final double CAPITAL = 10_000;
    static final String BENCHMARK = "Buy & Hold";

    private final AgentConfigService agentService;
    private final AgentLabSupport support;
    private final TournamentRunRepository runRepository;

    public TournamentResult run(User user, TournamentRequest req) {
        long started = System.currentTimeMillis();

        List<Long> ids = req.agentIds().stream().distinct().toList();
        if (ids.size() > MAX_AGENTS) {
            throw new IllegalArgumentException("Maximum " + MAX_AGENTS + " agents par tournoi");
        }
        List<AgentConfig> agents = ids.stream().map(id -> agentService.findOne(id, user)).toList();
        List<TradingPolicy> policies = new ArrayList<>();
        for (AgentConfig agent : agents) {
            try {
                policies.add(support.policy(agent));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("« " + agent.getName() + " » : " + e.getMessage());
            }
        }

        Asset asset = support.asset(req.symbol());
        AssetDynamics dynamics = support.dynamics(asset);
        PriceModel model = req.model() == null ? PriceModel.REGIME_SWITCHING : req.model();
        int requested = clamp(req.paths(), 1000, 200, 3000);
        int horizon = clamp(req.horizonDays(), 252, 20, 504);
        boolean antithetic = req.antithetic() == null || req.antithetic();
        long seed = req.seed() != null ? req.seed() : ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
        double s0 = asset.getPrice().doubleValue();

        int competitors = agents.size() + 1; // le dernier = buy & hold
        int bench = competitors - 1;
        int groups = antithetic ? (requested + 1) / 2 : requested;
        int n = antithetic ? groups * 2 : requested;
        int[] days = sampleDays(horizon);

        double[][] returns = new double[competitors][n];
        double[][] sharpes = new double[competitors][n];
        double[][] drawdowns = new double[competitors][n];
        double[][] trades = new double[competitors][n];
        double[][][] equity = new double[competitors][days.length][n];

        // ---- 1. Memes trajectoires pour tout le monde ----
        IntStream.range(0, groups).parallel().forEach(g -> {
            SplittableRandom rng = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + g);
            Shocks shocks = PathGenerator.drawShocks(WARMUP + horizon, dynamics, model, rng);
            int copies = antithetic ? 2 : 1;
            for (int c = 0; c < copies; c++) {
                double[] path = PathGenerator.build(1.0, shocks, dynamics, model, c == 1);
                double scale = s0 / path[WARMUP];
                for (int i = 0; i < path.length; i++) path[i] *= scale;
                int k = g * copies + c;

                for (int a = 0; a < agents.size(); a++) {
                    Backtester.Result r = Backtester.run(path, WARMUP, policies.get(a), agents.get(a).risk(),
                            dynamics.sigma(), CAPITAL);
                    returns[a][k] = r.totalReturn();
                    sharpes[a][k] = r.sharpe();
                    drawdowns[a][k] = r.maxDrawdown();
                    trades[a][k] = r.trades();
                    for (int d = 0; d < days.length; d++) equity[a][d][k] = r.equity()[days[d]] / CAPITAL * 100;
                }

                double[] bh = new double[horizon + 1];
                for (int t = 0; t <= horizon; t++) bh[t] = path[WARMUP + t] / path[WARMUP];
                returns[bench][k] = bh[horizon] - 1;
                sharpes[bench][k] = Stats.sharpe(bh);
                drawdowns[bench][k] = Stats.maxDrawdown(bh);
                for (int d = 0; d < days.length; d++) equity[bench][d][k] = bh[days[d]] * 100;
            }
        });

        // ---- 2. Classement par Sharpe moyen ----
        String[] names = new String[competitors];
        for (int a = 0; a < agents.size(); a++) names[a] = agents.get(a).getName();
        names[bench] = BENCHMARK;
        dedupe(names);

        double[] meanSharpe = new double[competitors];
        for (int c = 0; c < competitors; c++) meanSharpe[c] = Stats.mean(sharpes[c]);
        int[] order = IntStream.range(0, competitors).boxed()
                .sorted(Comparator.comparingDouble((Integer c) -> -meanSharpe[c]))
                .mapToInt(Integer::intValue).toArray();

        List<Competitor> ranking = new ArrayList<>();
        for (int rank = 0; rank < competitors; rank++) {
            int c = order[rank];
            AgentConfig agent = c < agents.size() ? agents.get(c) : null;
            double[] sorted = Stats.sortedCopy(returns[c]);
            long profitable = Arrays.stream(returns[c]).filter(r -> r > 0).count();
            ranking.add(new Competitor(rank + 1, agent == null ? null : agent.getId(), names[c],
                    agent == null ? null : agent.getStrategy(), agent == null ? 0 : agent.getRiskLevel(),
                    Stats.mean(returns[c]), Stats.percentile(sorted, 0.5), Stats.std(returns[c]),
                    Stats.valueAtRisk(sorted, 0.95), Stats.expectedShortfall(sorted, 0.95),
                    (double) profitable / n, meanSharpe[c], Stats.mean(drawdowns[c]), Stats.mean(trades[c]),
                    Stats.percentile(sorted, 0.05), Stats.percentile(sorted, 0.25), Stats.percentile(sorted, 0.5),
                    Stats.percentile(sorted, 0.75), Stats.percentile(sorted, 0.95)));
        }

        List<EquityPoint> medianEquity = new ArrayList<>();
        for (int d = 0; d < days.length; d++) {
            double[] values = new double[competitors];
            for (int rank = 0; rank < competitors; rank++) {
                values[rank] = round2(Stats.percentile(Stats.sortedCopy(equity[order[rank]][d]), 0.5));
            }
            medianEquity.add(new EquityPoint(days[d], values));
        }

        // ---- 3. Tests statistiques apparies ----
        // Avec variables antithetiques, les 2 trajectoires d'une paire sont correlees : l'unite statistique
        // independante est la paire, on teste donc sur les moyennes par paire.
        List<PairwiseTest> returnTests = pairwise(order, antithetic ? pairAverages(returns) : returns, names, seed, true);
        List<PairwiseTest> sharpeTests = pairwise(order, antithetic ? pairAverages(sharpes) : sharpes, names, seed + 1, false);
        String conclusion = conclusion(ranking, sharpeTests, order, bench, names);

        long elapsed = System.currentTimeMillis() - started;

        TournamentRun run = new TournamentRun();
        run.setOwner(user);
        run.setAgentIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        run.setRanking(ranking.stream().map(Competitor::name).collect(Collectors.joining(" > ")));
        run.setWinner(ranking.get(0).name());
        run.setSymbol(asset.getSymbol());
        run.setModel(model);
        run.setPaths(n);
        run.setHorizonDays(horizon);
        run.setSeed(seed);
        run.setAntithetic(antithetic);
        run.setConclusion(conclusion.length() > 1000 ? conclusion.substring(0, 1000) : conclusion);
        run.setElapsedMs(elapsed);
        runRepository.save(run);

        return new TournamentResult(run.getId(), asset.getSymbol(), model, n, horizon, seed, antithetic, elapsed,
                BOOTSTRAP_SAMPLES, ranking, medianEquity, returnTests, sharpeTests, conclusion);
    }

    public List<TournamentRun> history(User user) {
        return runRepository.findTop20ByOwnerIdOrderByCreatedAtDescIdDesc(user.getId());
    }

    // ---------------------------------------------------------------------------------------

    /** Compare chaque paire (A mieux classe que B) sur les differences par trajectoire. */
    static List<PairwiseTest> pairwise(int[] order, double[][] metric, String[] names, long seed, boolean percent) {
        int k = order.length;
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) pairs.add(new int[] {i, j});
        }

        double[][] raw = new double[pairs.size()][];
        IntStream.range(0, pairs.size()).parallel().forEach(p -> {
            int[] pair = pairs.get(p);
            raw[p] = compare(metric[order[pair[0]]], metric[order[pair[1]]],
                    new SplittableRandom(seed * 31 + p * 0x9E3779B97F4A7C15L));
        });

        double[] pValues = Arrays.stream(raw).mapToDouble(r -> r[4]).toArray();
        double[] holm = Stats.holm(pValues);

        List<PairwiseTest> tests = new ArrayList<>();
        for (int p = 0; p < pairs.size(); p++) {
            int i = pairs.get(p)[0];
            int j = pairs.get(p)[1];
            String a = names[order[i]];
            String b = names[order[j]];
            double[] r = raw[p];
            boolean significant = holm[p] < ALPHA;
            String diff = percent
                    ? String.format(Locale.FRANCE, "%+.2f pts de rendement", r[0] * 100)
                    : String.format(Locale.FRANCE, "%+.3f de Sharpe", r[0]);
            String verdict = significant
                    ? String.format(Locale.FRANCE, "%s %s %s (%s, p corrigée %s)",
                            r[0] > 0 ? a : b, "fait significativement mieux que", r[0] > 0 ? b : a, diff, formatP(holm[p]))
                    : String.format(Locale.FRANCE, "Pas de différence significative entre %s et %s (p corrigée %s)",
                            a, b, formatP(holm[p]));
            tests.add(new PairwiseTest(i, j, a, b, r[0], r[1], r[2], r[3], r[4], holm[p], r[5], r[6], r[7], r[8],
                    r[9], significant, verdict));
        }
        return tests;
    }

    /**
     * @return {moyenne, IC bas, IC haut, t, p(t), p(bootstrap), p(signes), taux de victoire, d de Cohen, gain de variance}
     */
    static double[] compare(double[] x, double[] y, SplittableRandom rng) {
        int n = x.length;
        double[] d = new double[n];
        int wins = 0;
        int losses = 0;
        for (int i = 0; i < n; i++) {
            d[i] = x[i] - y[i];
            if (d[i] > 1e-12) wins++;
            else if (d[i] < -1e-12) losses++;
        }
        double mean = Stats.mean(d);
        double sd = Stats.std(d);

        // test t apparie
        double t;
        double pT;
        if (sd < 1e-15) {
            t = Math.abs(mean) < 1e-15 ? 0 : Math.signum(mean) * Double.POSITIVE_INFINITY;
            pT = Math.abs(mean) < 1e-15 ? 1 : 0;
        } else {
            t = mean / (sd / Math.sqrt(n));
            pT = Stats.studentTwoSidedP(t, n - 1);
        }

        // bootstrap (percentile) de la moyenne des differences
        double[] means = new double[BOOTSTRAP_SAMPLES];
        int below = 0;
        int above = 0;
        for (int b = 0; b < BOOTSTRAP_SAMPLES; b++) {
            double s = 0;
            for (int i = 0; i < n; i++) s += d[rng.nextInt(n)];
            means[b] = s / n;
            if (means[b] <= 0) below++;
            if (means[b] >= 0) above++;
        }
        Arrays.sort(means);
        double pBoot = Math.min(1, 2.0 * (Math.min(below, above) + 1) / (BOOTSTRAP_SAMPLES + 1));

        // test des signes (approximation normale, correction de continuite)
        int m = wins + losses;
        double pSign = 1;
        if (m > 0) {
            double z = (Math.abs(wins - m / 2.0) - 0.5) / Math.sqrt(m / 4.0);
            pSign = Math.min(1, 2 * (1 - Stats.normalCdf(Math.max(0, z))));
        }

        double varDiff = sd * sd;
        double varIndependent = Math.pow(Stats.std(x), 2) + Math.pow(Stats.std(y), 2);
        double varianceFactor = varDiff < 1e-18 ? 1e6 : Math.min(1e6, varIndependent / varDiff);

        return new double[] {mean, Stats.percentile(means, 0.025), Stats.percentile(means, 0.975), t, pT, pBoot,
                pSign, (double) wins / n, sd < 1e-15 ? 0 : mean / sd, varianceFactor};
    }

    private static String conclusion(List<Competitor> ranking, List<PairwiseTest> sharpeTests, int[] order,
                                     int bench, String[] names) {
        Competitor first = ranking.get(0);
        StringBuilder sb = new StringBuilder(String.format(Locale.FRANCE,
                "%s obtient le meilleur Sharpe moyen (%.2f).", first.name(), first.meanSharpe()));
        if (ranking.size() > 1) {
            PairwiseTest vsSecond = find(sharpeTests, 0, 1);
            sb.append(vsSecond.significant()
                    ? String.format(Locale.FRANCE, " Son avance sur %s est statistiquement significative (p corrigée %s).",
                            vsSecond.nameB(), formatP(vsSecond.pHolm()))
                    : String.format(Locale.FRANCE, " Son avance sur %s n'est pas statistiquement significative (p corrigée %s) : on ne peut pas les départager.",
                            vsSecond.nameB(), formatP(vsSecond.pHolm())));
        }
        int benchRank = -1;
        for (int r = 0; r < order.length; r++) if (order[r] == bench) benchRank = r;
        if (benchRank > 0) {
            PairwiseTest vsBench = find(sharpeTests, 0, benchRank);
            if (vsBench != null && benchRank != 1) {
                sb.append(vsBench.significant()
                        ? " Il bat aussi significativement le " + names[bench] + "."
                        : " Sa différence avec le " + names[bench] + " n'est pas significative.");
            }
        } else if (benchRank == 0) {
            sb.append(" Aucun agent ne fait mieux que de simplement acheter et conserver l'actif.");
        }
        return sb.toString();
    }

    private static PairwiseTest find(List<PairwiseTest> tests, int a, int b) {
        return tests.stream().filter(t -> t.a() == a && t.b() == b).findFirst().orElse(null);
    }

    static String formatP(double p) {
        return p < 0.0001 ? "< 0,0001" : String.format(Locale.FRANCE, "= %.4f", p);
    }

    /** Moyenne de chaque paire antithetique (Z, -Z) : observations independantes. */
    static double[][] pairAverages(double[][] metric) {
        double[][] out = new double[metric.length][];
        for (int c = 0; c < metric.length; c++) {
            double[] avg = new double[metric[c].length / 2];
            for (int g = 0; g < avg.length; g++) avg[g] = (metric[c][2 * g] + metric[c][2 * g + 1]) / 2;
            out[c] = avg;
        }
        return out;
    }

    /** Jours echantillonnes pour les courbes medianes (environ 60 points). */
    private static int[] sampleDays(int horizon) {
        int step = Math.max(1, horizon / EQUITY_POINTS);
        List<Integer> days = new ArrayList<>();
        for (int d = 0; d < horizon; d += step) days.add(d);
        days.add(horizon);
        return days.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Deux agents avec le meme nom -> "Nom (2)". */
    private static void dedupe(String[] names) {
        for (int i = 0; i < names.length; i++) {
            int count = 1;
            for (int j = i + 1; j < names.length; j++) {
                if (names[j].equals(names[i])) names[j] = names[j] + " (" + ++count + ")";
            }
        }
    }

    private static int clamp(Integer value, int def, int min, int max) {
        int v = value == null ? def : value;
        return Math.max(min, Math.min(max, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
