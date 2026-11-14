package tn.esprit.marketsim.agent.portfolio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.agent.engine.TradingPolicy;
import tn.esprit.marketsim.agent.realdata.MarketSeriesRepository;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.market.Asset;

/**
 * Portefeuille multi-actifs : l'agent gere en meme temps tous ses actifs autorises, chacun avec une part
 * du capital (poids). On mesure le rendement global, la baisse maximale et l'effet de la diversification
 * (correlation moyenne entre actifs) par rapport a un seul actif.
 */
@Service
@RequiredArgsConstructor
public class PortfolioService {

    static final int WARMUP = 60;

    private final AgentLabSupport support;
    private final MarketSeriesRepository seriesRepository;

    /**
     * @param weights  poids par actif (normalises a 100 %) ; vide = parts egales
     * @param realData utiliser l'historique reel quand il a ete telecharge (sinon marche simule)
     */
    public record PortfolioRequest(@Min(40) @Max(504) Integer days, Long seed, Map<String, Double> weights,
                                   @Min(100) Double capital, Boolean realData) {
    }

    public record AssetResult(String symbol, double weight, String source, double agentReturn, double benchmarkReturn,
                              double maxDrawdown, int trades, int wins) {
    }

    /**
     * @param diversification 1 - correlation moyenne des rendements journaliers (1 = actifs independants)
     * @param bestSingleReturn meilleur rendement d'un actif seul (pour comparer au portefeuille)
     */
    public record PortfolioResult(String agentName, long seed, int days, double capital, double agentReturn,
                                  double benchmarkReturn, double maxDrawdown, double benchmarkMaxDrawdown, double sharpe,
                                  double diversification, double avgCorrelation, double worstSingleDrawdown,
                                  List<AssetResult> assets, double[] equity, double[] benchmark, String verdict) {
    }

    @Transactional(readOnly = true)
    public PortfolioResult run(AgentConfig agent, PortfolioRequest req) {
        List<String> symbols = Arrays.stream(String.valueOf(agent.getAllowedAssets()).split(","))
                .map(String::trim).filter(s -> !s.isEmpty() && !s.equals("null")).distinct().toList();
        if (symbols.isEmpty()) throw new IllegalArgumentException("Aucun actif autorisé pour cet agent");
        int days = req.days() == null ? 252 : req.days();
        long seed = req.seed() != null ? req.seed() : ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
        double capital = req.capital() == null ? 10_000 : req.capital();
        boolean useReal = req.realData() == null || req.realData();
        Map<String, Double> weights = weights(symbols, req.weights());
        TradingPolicy policy = support.policy(agent);
        double risk = agent.risk();

        double[] equity = new double[days + 1];
        double[] bench = new double[days + 1];
        List<double[]> dailyReturns = new ArrayList<>();
        List<AssetResult> assets = new ArrayList<>();
        int i = 0;
        for (String symbol : symbols) {
            Asset asset = support.asset(symbol);
            AssetDynamics dynamics = support.dynamics(asset);
            double[] real = useReal ? realPath(asset.getSymbol(), days) : null;
            double[] path = real != null ? real : simulated(asset, dynamics, days, seed + 31L * i);
            double part = capital * weights.get(symbol);
            Backtester.Result r = Backtester.run(path, WARMUP, policy, risk, dynamics.sigma(), part);
            for (int d = 0; d <= days; d++) {
                equity[d] += r.equity()[d];
                bench[d] += part * path[WARMUP + d] / path[WARMUP];
            }
            double[] rets = new double[days];
            for (int d = 1; d <= days; d++) rets[d - 1] = path[WARMUP + d] / path[WARMUP + d - 1] - 1;
            dailyReturns.add(rets);
            assets.add(new AssetResult(asset.getSymbol(), round(weights.get(symbol)), real != null ? "REEL" : "SIMULE",
                    round(r.totalReturn()), round(r.benchmarkReturn()), round(r.maxDrawdown()), r.trades(), r.wins()));
            i++;
        }
        double avgCorr = averageCorrelation(dailyReturns);
        double agentReturn = equity[days] / capital - 1;
        double benchReturn = bench[days] / capital - 1;
        double maxDd = Stats.maxDrawdown(equity);
        double worstSingle = assets.stream().mapToDouble(AssetResult::maxDrawdown).min().orElse(0);
        return new PortfolioResult(agent.getName(), seed, days, capital, round(agentReturn), round(benchReturn),
                round(maxDd), round(Stats.maxDrawdown(bench)), round(Stats.sharpe(equity)), round(1 - avgCorr),
                round(avgCorr), round(worstSingle), assets, rounded(equity), rounded(bench),
                verdict(agentReturn, benchReturn, maxDd, worstSingle, avgCorr, symbols.size()));
    }

    static Map<String, Double> weights(List<String> symbols, Map<String, Double> requested) {
        Map<String, Double> out = new LinkedHashMap<>();
        double total = 0;
        for (String s : symbols) {
            Double w = requested == null ? null : requested.get(s);
            double v = w == null || w <= 0 ? (requested == null || requested.isEmpty() ? 1 : 0) : w;
            out.put(s, v);
            total += v;
        }
        if (total <= 0) {
            symbols.forEach(s -> out.put(s, 1.0));
            total = symbols.size();
        }
        for (String s : symbols) out.put(s, out.get(s) / total);
        return out;
    }

    /** Moyenne des correlations deux a deux des rendements journaliers (0 si un seul actif). */
    static double averageCorrelation(List<double[]> series) {
        int n = series.size();
        if (n < 2) return 1;
        double sum = 0;
        int pairs = 0;
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                sum += correlation(series.get(a), series.get(b));
                pairs++;
            }
        }
        return sum / pairs;
    }

    static double correlation(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double cov = 0;
        double vx = 0;
        double vy = 0;
        for (int i = 0; i < n; i++) {
            cov += (x[i] - mx) * (y[i] - my);
            vx += (x[i] - mx) * (x[i] - mx);
            vy += (y[i] - my) * (y[i] - my);
        }
        return vx <= 0 || vy <= 0 ? 0 : cov / Math.sqrt(vx * vy);
    }

    private double[] realPath(String symbol, int days) {
        try {
            return seriesRepository.findBySymbol(symbol)
                    .map(row -> PriceSeries.decode(row.getSymbol(), row.getSource(), row.getData()).closes())
                    .filter(c -> c.length >= WARMUP + days + 1)
                    .map(c -> Arrays.copyOfRange(c, c.length - WARMUP - days - 1, c.length))
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double[] simulated(Asset asset, AssetDynamics d, int days, long seed) {
        double[] path = PathGenerator.generate(1.0, WARMUP + days, d, PriceModel.REGIME_SWITCHING, new SplittableRandom(seed));
        double scale = asset.getPrice().doubleValue() / path[WARMUP];
        for (int i = 0; i < path.length; i++) path[i] *= scale;
        return path;
    }

    static String verdict(double ret, double bench, double dd, double worstSingle, double corr, int n) {
        if (n == 1) {
            return "Un seul actif autorisé : ajoute des actifs à l'agent pour profiter de la diversification.";
        }
        String perf = ret >= bench
                ? String.format(Locale.FRANCE, "L'agent bat « acheter et garder » (%+.1f %% contre %+.1f %%).", ret * 100, bench * 100)
                : String.format(Locale.FRANCE, "L'agent fait moins bien qu'« acheter et garder » (%+.1f %% contre %+.1f %%).", ret * 100, bench * 100);
        String div = dd > worstSingle
                ? String.format(Locale.FRANCE, " La diversification limite la baisse maximale à %.1f %%, contre %.1f %% pour le pire actif seul.",
                        Math.abs(dd) * 100, Math.abs(worstSingle) * 100)
                : " La diversification n'a pas réduit la baisse maximale sur ce marché.";
        String c = corr > 0.6 ? " Les actifs bougent beaucoup ensemble (corrélation élevée) : choisis des actifs plus différents."
                : corr < 0.3 ? " Les actifs sont peu corrélés : bonne diversification." : "";
        return perf + div + c;
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    private static double[] rounded(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = Math.round(v[i] * 100) / 100.0;
        return out;
    }
}
