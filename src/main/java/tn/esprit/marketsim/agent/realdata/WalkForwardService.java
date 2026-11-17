package tn.esprit.marketsim.agent.realdata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.CurvePoint;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.Performance;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.PricePoint;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.SeriesInfo;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.SignificanceTest;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.WalkForwardRequest;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.WalkForwardResult;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.WindowDto;
import tn.esprit.marketsim.agent.engine.LinearPolicy;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.QLearningPolicy;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.agent.realdata.WalkForward.PolicySupplier;
import tn.esprit.marketsim.agent.tournament.TournamentService;
import tn.esprit.marketsim.agent.training.QLearningTrainer;
import tn.esprit.marketsim.ledger.AuditLedger;
import tn.esprit.marketsim.user.User;

/**
 * Backtest walk-forward d'un agent sur des cours reels, avec verdict statistique honnete
 * (test apparie agent - buy &amp; hold sur les fenetres hors echantillon).
 */
@Service
@RequiredArgsConstructor
public class WalkForwardService {

    static final int CHART_POINTS = 360;
    /** En dessous de ce nombre de fenetres, le test statistique n'a presque aucune puissance. */
    static final int MIN_WINDOWS_FOR_TEST = 3;

    private final MarketDataService marketData;
    private final AgentLabSupport support;
    private final AuditLedger ledger;

    /** Historique reel d'un actif, pour l'onglet Donnees reelles. */
    public SeriesInfo seriesInfo(PriceSeries s) {
        return new SeriesInfo(s.symbol(), s.source(), s.size(), s.dates()[0], s.dates()[s.size() - 1],
                s.closes()[0], s.closes()[s.size() - 1], s.hash(), prices(s, 0, s.size()));
    }

    public WalkForwardResult run(AgentConfig agent, User user, WalkForwardRequest req) {
        long started = System.currentTimeMillis();
        PriceSeries series = marketData.series(req.symbol());
        int train = clamp(req.trainDays(), 504, 252, 1260);
        int test = clamp(req.testDays(), 126, 21, 504);
        boolean learns = agent.getStrategy() == AgentStrategy.Q_LEARNING;
        int episodes = learns ? clamp(req.episodes(), 300, 50, 2000) : 0;
        long seed = req.seed() != null ? req.seed() : ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);

        int windows = WalkForward.windowCount(series.size(), train, test);
        if (windows < 1) {
            throw new IllegalArgumentException("Historique trop court (" + series.size() + " jours) pour entraîner sur "
                    + train + " jours puis tester sur " + test + " jours.");
        }

        double risk = agent.risk();
        PolicySupplier supplier = learns
                ? (prices, sigma, s) -> new QLearningPolicy(QLearningTrainer.trainOnSeries(prices, sigma, risk, episodes, s))
                : (prices, sigma, s) -> LinearPolicy.of(agent.getStrategy(), risk);
        WalkForward.Result wf = WalkForward.run(series.closes(), series.dates(), train, test, risk, supplier, seed);

        double[] agentWindows = wf.windows().stream().mapToDouble(WalkForward.Window::agentReturn).toArray();
        double[] benchWindows = wf.windows().stream().mapToDouble(WalkForward.Window::benchmarkReturn).toArray();
        double inSampleExcess = wf.windows().stream().mapToDouble(w -> w.inSampleReturn() - w.inSampleBenchmark()).average().orElse(0);
        double outExcess = Stats.mean(agentWindows) - Stats.mean(benchWindows);
        double overfitGap = inSampleExcess - outExcess;

        SignificanceTest significance = null;
        if (windows >= MIN_WINDOWS_FOR_TEST) {
            double[] c = TournamentService.compare(agentWindows, benchWindows, new SplittableRandom(seed));
            significance = new SignificanceTest(windows, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]);
        }

        Performance agentPerf = performance(wf.agentCurve());
        Performance benchPerf = performance(wf.benchmarkCurve());
        String verdict = verdict(windows, agentPerf, benchPerf, significance, overfitGap, learns);

        List<WindowDto> windowDtos = wf.windows().stream().map(w -> new WindowDto(w.index(), w.trainFrom(), w.trainTo(),
                w.testFrom(), w.testTo(), w.agentReturn(), w.benchmarkReturn(), w.excess(), w.inSampleReturn(),
                w.inSampleBenchmark(), w.maxDrawdown(), w.trades(), w.exposure())).toList();

        WalkForwardResult result = new WalkForwardResult(series.symbol(), series.source(), series.hash(),
                series.dates()[0], series.dates()[series.size() - 1], series.size(), agent.getStrategy(),
                agent.getRiskLevel() == null ? 50 : agent.getRiskLevel(), train, test, episodes, seed,
                System.currentTimeMillis() - started, windowDtos, agentPerf, benchPerf, significance, overfitGap,
                curve(wf), prices(series, 0, series.size()), verdict, null);

        Long auditId = ledger.record("WALK_FORWARD", null, user.getId(), agent.getId(),
                String.format(Locale.FRANCE, "Walk-forward %s · %s · agent %+.1f %% vs B&H %+.1f %%", series.symbol(),
                        agent.getName(), agentPerf.totalReturn() * 100, benchPerf.totalReturn() * 100),
                auditPayload(agent, result));
        return result.withAuditRecord(auditId);
    }

    // ---------------------------------------------------------------------------------------

    private Map<String, Object> auditPayload(AgentConfig agent, WalkForwardResult r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("agentName", agent.getName());
        p.put("strategy", String.valueOf(r.strategy()));
        p.put("riskLevel", r.riskLevel());
        p.put("symbol", r.symbol());
        p.put("dataSource", r.source());
        p.put("dataHash", r.dataHash());
        p.put("dataFrom", r.dataFrom().toString());
        p.put("dataTo", r.dataTo().toString());
        p.put("bars", r.bars());
        p.put("trainDays", r.trainDays());
        p.put("testDays", r.testDays());
        p.put("episodes", r.episodes());
        p.put("seed", r.seed());
        p.put("windows", r.windows().size());
        p.put("agentReturn", r.agent().totalReturn());
        p.put("agentSharpe", r.agent().sharpe());
        p.put("agentMaxDrawdown", r.agent().maxDrawdown());
        p.put("benchmarkReturn", r.benchmark().totalReturn());
        p.put("benchmarkSharpe", r.benchmark().sharpe());
        p.put("benchmarkMaxDrawdown", r.benchmark().maxDrawdown());
        p.put("overfitGap", r.overfitGap());
        if (r.test() != null) {
            p.put("meanExcessPerWindow", r.test().meanExcess());
            p.put("pValue", r.test().pValue());
            p.put("pBootstrap", r.test().pBootstrap());
        }
        p.put("verdict", r.verdict());
        return p;
    }

    public static Performance performance(double[] curve) {
        double total = curve[curve.length - 1] / curve[0] - 1;
        double years = (curve.length - 1) / (double) PathGenerator.TRADING_DAYS;
        double annualized = years <= 0 ? 0 : Math.pow(1 + total, 1 / years) - 1;
        return new Performance(total, annualized, Stats.sharpe(curve), Stats.maxDrawdown(curve));
    }

    static String verdict(int windows, Performance agent, Performance bench, SignificanceTest t, double overfitGap,
                          boolean learns) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.FRANCE, "Hors échantillon sur %d fenêtres : agent %+.1f %% contre %+.1f %% pour le buy & hold. ",
                windows, agent.totalReturn() * 100, bench.totalReturn() * 100));
        if (t == null) {
            sb.append("Moins de ").append(MIN_WINDOWS_FOR_TEST)
                    .append(" fenêtres : impossible de conclure statistiquement (raccourcissez la fenêtre de test).");
        } else if (t.pValue() < 0.05 && t.meanExcess() > 0) {
            sb.append(String.format(Locale.FRANCE, "Surperformance statistiquement significative : +%.1f %% par fenêtre (p = %.3f, IC bootstrap [%+.1f %% ; %+.1f %%]). "
                            + "Un historique n'est qu'un seul chemin possible : confirmez par Monte Carlo.",
                    t.meanExcess() * 100, t.pValue(), t.ciLow() * 100, t.ciHigh() * 100));
        } else if (t.meanExcess() > 0) {
            sb.append(String.format(Locale.FRANCE, "L'agent fait mieux en moyenne (+%.1f %% par fenêtre) mais l'écart n'est PAS statistiquement significatif (p = %.2f) : "
                    + "la chance ne peut pas être exclue.", t.meanExcess() * 100, t.pValue()));
        } else {
            sb.append(String.format(Locale.FRANCE, t.pValue() < 0.05
                            ? "L'agent fait significativement MOINS bien que le buy & hold en rendement brut (%+.1f %% par fenêtre, p = %.3f). "
                            : "L'agent ne bat pas le buy & hold en rendement brut sur données réelles (%+.1f %% par fenêtre, p = %.2f). ",
                    t.meanExcess() * 100, t.pValue()));
            if (agent.maxDrawdown() > bench.maxDrawdown() + 0.05) {
                sb.append(String.format(Locale.FRANCE, "En revanche il réduit la perte maximale (%.0f %% contre %.0f %%). ",
                        agent.maxDrawdown() * 100, bench.maxDrawdown() * 100));
            }
            if (agent.sharpe() > bench.sharpe() + 0.05) {
                sb.append(String.format(Locale.FRANCE, "Son ratio de Sharpe est meilleur (%.2f contre %.2f) : meilleur rendement par unité de risque, "
                        + "écart non testé statistiquement.", agent.sharpe(), bench.sharpe()));
            }
        }
        if (learns && overfitGap > 0.05) {
            sb.append(String.format(Locale.FRANCE, " Signe de sur-apprentissage : l'agent bat le marché de %.1f points de plus sur ce qu'il a appris que sur ce qu'il n'a pas vu.",
                    overfitGap * 100));
        }
        return sb.toString().trim();
    }

    /** Courbes bout a bout, base 100, sous-echantillonnees pour le graphique. */
    private static List<CurvePoint> curve(WalkForward.Result wf) {
        int n = wf.agentCurve().length;
        List<CurvePoint> out = new ArrayList<>();
        for (int i : sample(n, CHART_POINTS)) {
            out.add(new CurvePoint(wf.dates()[i], round(wf.agentCurve()[i] / WalkForward.CAPITAL * 100),
                    round(wf.benchmarkCurve()[i] / WalkForward.CAPITAL * 100)));
        }
        return out;
    }

    private static List<PricePoint> prices(PriceSeries s, int from, int to) {
        List<PricePoint> out = new ArrayList<>();
        for (int i : sample(to - from, CHART_POINTS)) {
            out.add(new PricePoint(s.dates()[from + i], round(s.closes()[from + i])));
        }
        return out;
    }

    /** Indices regulierement espaces, dernier point inclus. */
    static int[] sample(int n, int max) {
        if (n <= max) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            return all;
        }
        int[] idx = new int[max];
        for (int i = 0; i < max; i++) idx[i] = (int) Math.round((double) i * (n - 1) / (max - 1));
        return idx;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static int clamp(Integer value, int def, int min, int max) {
        return Math.max(min, Math.min(max, value == null ? def : value));
    }
}
