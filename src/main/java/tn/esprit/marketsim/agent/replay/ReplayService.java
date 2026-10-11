package tn.esprit.marketsim.agent.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.CrashScenario;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PathGenerator.Shocks;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.market.Asset;

/**
 * Mode replay (demo) : un seul marche simule, l'agent y trade et chacune de ses actions
 * est enregistree jour par jour. Le frontend rejoue le marche en accelere et l'utilisateur
 * trade a la main sur les memes prix (humain vs agent vs buy &amp; hold).
 */
@Service
@RequiredArgsConstructor
public class ReplayService {

    static final int WARMUP = 60;
    /** Jours d'historique montres avant le debut, pour donner du contexte au joueur. */
    static final int CONTEXT_DAYS = 40;
    public static final double CAPITAL = 10_000;

    private final AgentLabSupport support;

    public record ReplayRequest(@NotBlank String symbol, Integer days, PriceModel model, Long seed, Double crashPct) {
    }

    /**
     * @param event 0 = rien, 1 = achat, 2 = vente, 3 = stop-loss, 4 = take-profit
     */
    public record ReplayDay(int day, double price, AgentAction action, double confidence, int event,
                            int agentPosition, double agentEquity, double benchmarkEquity) {
    }

    /**
     * @param context prix des jours precedant le debut (contexte)
     * @param days    jours rejoues (day = 0 au debut)
     */
    public record ReplayResult(String symbol, PriceModel model, long seed, int horizonDays, double capital,
                               double positionFraction, double costRate, Integer crashDay, double[] context,
                               List<ReplayDay> days, double agentReturn, double benchmarkReturn,
                               double agentMaxDrawdown, int agentTrades) {
    }

    public ReplayResult replay(AgentConfig agent, ReplayRequest req) {
        Asset asset = support.asset(req.symbol());
        AssetDynamics dynamics = support.dynamics(asset);
        PriceModel model = req.model() == null ? PriceModel.REGIME_SWITCHING : req.model();
        int horizon = Math.max(40, Math.min(504, req.days() == null ? 252 : req.days()));
        long seed = req.seed() != null ? req.seed() : ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);

        Shocks shocks = PathGenerator.drawShocks(WARMUP + horizon, dynamics, model, new SplittableRandom(seed));
        Integer crashDay = null;
        if (req.crashPct() != null && req.crashPct() > 0) {
            crashDay = horizon / 3 + new SplittableRandom(seed + 1).nextInt(horizon / 3);
            shocks = new CrashScenario(req.crashPct() / 100, crashDay, 2.0, 20).apply(shocks, WARMUP);
        }
        double[] path = PathGenerator.build(1.0, shocks, dynamics, model, false);
        double s0 = asset.getPrice().doubleValue();
        double scale = s0 / path[WARMUP];
        for (int i = 0; i < path.length; i++) path[i] *= scale;

        Backtester.Result r = Backtester.run(path, WARMUP, support.policy(agent), agent.risk(),
                dynamics.sigma(), CAPITAL, true);
        Backtester.Trace trace = r.trace();

        List<ReplayDay> days = new ArrayList<>();
        for (int d = 0; d <= horizon; d++) {
            days.add(new ReplayDay(d, round(path[WARMUP + d]), trace.actions()[d], trace.confidence()[d],
                    trace.events()[d], trace.positions()[d], round(r.equity()[d]),
                    round(CAPITAL * path[WARMUP + d] / path[WARMUP])));
        }
        double[] context = new double[CONTEXT_DAYS];
        for (int i = 0; i < CONTEXT_DAYS; i++) context[i] = round(path[WARMUP - CONTEXT_DAYS + i]);

        return new ReplayResult(asset.getSymbol(), model, seed, horizon, CAPITAL,
                Backtester.positionFraction(agent.risk()), Backtester.COST, crashDay, context, days,
                r.totalReturn(), r.benchmarkReturn(), r.maxDrawdown(), r.trades());
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
