package tn.esprit.marketsim.trading;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.MarketSeries;
import tn.esprit.marketsim.agent.realdata.MarketSeriesRepository;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.market.Asset;

/**
 * Volatilite d'un actif pour le module 2.
 * <ol>
 *   <li>Serie REELLE (Yahoo / CSV) deja en base via le module 6 : rendements journaliers des {@value #WINDOW}
 *       dernieres seances.</li>
 *   <li>Sinon σ de calibration par classe d'actif ({@link AssetDynamics}) et, en tache de fond, un telechargement
 *       via {@link MarketDataService} (au plus une tentative par symbole et par {@code RETRY}) : la requete de
 *       l'utilisateur n'attend JAMAIS le reseau, et hors ligne le repli est immediat.</li>
 * </ol>
 * Resultat garde en memoire {@code TTL} (cache partage).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolatilityService {

    static final int WINDOW = 250;
    static final int MIN_RETURNS = 60;
    static final Duration TTL = Duration.ofMinutes(30);
    static final Duration RETRY = Duration.ofHours(1);

    private final TradingAssetRepository assets;
    private final MarketSeriesRepository seriesRepository;
    private final MarketDataService marketData;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastDownloadAttempt = new ConcurrentHashMap<>();

    /**
     * @param sigmaAnnual     volatilite annualisee
     * @param sortedReturns   rendements journaliers tries (null si pas de serie reelle)
     * @param closesByDate    cloture par date (null si pas de serie reelle), pour aligner les actifs entre eux
     * @param source          REAL ou MODEL
     */
    public record Profile(String symbol, double sigmaAnnual, double[] sortedReturns,
                          Map<LocalDate, Double> closesByDate, String source) {

        public double sigmaDaily() {
            return sigmaAnnual / Math.sqrt(PathGenerator.TRADING_DAYS);
        }

        public boolean real() {
            return sortedReturns != null;
        }
    }

    private record Cached(Profile profile, Instant at) {
    }

    public Profile profile(String symbol) {
        String key = PortfolioCalculator.key(symbol);
        Cached c = cache.get(key);
        if (c != null && c.at().plus(TTL).isAfter(Instant.now())) return c.profile();
        Profile p = load(key);
        cache.put(key, new Cached(p, Instant.now()));
        return p;
    }

    private Profile load(String key) {
        Asset asset = assets.findFirstBySymbolIgnoreCase(key).orElse(null);
        String symbol = asset == null ? key : asset.getSymbol();
        try {
            MarketSeries stored = seriesRepository.findBySymbol(symbol).orElse(null);
            if (stored != null && stored.getData() != null) {
                Profile real = fromSeries(symbol, PriceSeries.decode(symbol, stored.getSource(), stored.getData()));
                if (real != null) return real;
            }
        } catch (RuntimeException e) {
            log.warn("Volatilite {} : serie reelle illisible ({})", symbol, e.getMessage());
        }
        if (asset != null) prefetch(symbol);
        double sigma = asset == null || asset.getType() == null ? AssetDynamics.of(Asset.Type.STOCK).sigma()
                : AssetDynamics.of(asset.getType()).sigma();
        return new Profile(symbol, sigma, null, null, "MODEL");
    }

    /** Rendements simples des {@value #WINDOW} dernieres seances ; null si l'historique est trop court. */
    static Profile fromSeries(String symbol, PriceSeries s) {
        int n = s.size();
        if (n < MIN_RETURNS + 1) return null;
        int start = Math.max(0, n - WINDOW - 1);
        double[] r = new double[n - 1 - start];
        Map<LocalDate, Double> closes = new LinkedHashMap<>();
        for (int i = start; i < n; i++) {
            closes.put(s.dates()[i], s.closes()[i]);
            if (i > start) r[i - 1 - start] = s.closes()[i] / s.closes()[i - 1] - 1;
        }
        double sigma = Stats.std(r) * Math.sqrt(PathGenerator.TRADING_DAYS);
        double[] sorted = r.clone();
        Arrays.sort(sorted);
        return new Profile(symbol, sigma, sorted, closes, "REAL");
    }

    /** Telechargement en tache de fond (jamais bloquant), au plus une tentative par symbole et par heure. */
    private void prefetch(String symbol) {
        Instant last = lastDownloadAttempt.get(symbol);
        if (last != null && last.plus(RETRY).isAfter(Instant.now())) return;
        lastDownloadAttempt.put(symbol, Instant.now());
        CompletableFuture.runAsync(() -> {
            try {
                marketData.series(symbol);
                cache.remove(PortfolioCalculator.key(symbol));       // prochaine lecture : serie reelle
            } catch (RuntimeException e) {
                log.info("Volatilite {} : donnees reelles indisponibles, σ par classe d'actif ({})", symbol, e.getMessage());
            }
        });
    }
}
