package tn.esprit.marketsim.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.ticker.MarketTickerService;
import tn.esprit.marketsim.agent.ticker.MarketTickerService.Quote;
import tn.esprit.marketsim.agent.ticker.MarketTickerService.Ticker;

/**
 * Module 5 : moteur de prix. Il fait evoluer {@code Asset.price} dans le temps : c'est le seul prix de chaque actif,
 * lu par le trading (module 2, via {@code PriceProvider}), le portefeuille, le risque, la page Marche et le bandeau.
 * <p>
 * A chaque pas ({@code market.engine.interval-ms}, 3 s par defaut), pour chaque actif :
 * <ul>
 *   <li>si un cours reel recent est dans le cache du bandeau ({@link MarketTickerService}, Yahoo / CoinGecko), le prix
 *       est ancre dessus : prix = cours reel × exp(ecart), l'ecart suivant un processus de retour a la moyenne ;</li>
 *   <li>sinon, marche aleatoire (mouvement brownien geometrique) avec la volatilite de sa classe
 *       ({@link AssetDynamics}), ramenee au pas de temps.</li>
 * </ul>
 * Le thread du moteur ne fait JAMAIS d'appel reseau : il lit seulement le cache du bandeau. Les cours reels sont
 * rafraichis par un second thread ({@code market-quotes}), qui peut attendre le reseau sans bloquer les prix.
 * Les deux threads sont dedies : le planificateur Spring partage n'a qu'un thread, bloque par d'autres modules.
 */
@Slf4j
@Service
public class MarketPriceEngine {

    /** Correspondance symbole de l'actif → symbole du bandeau. Un actif absent est cherche sous son propre symbole. */
    public static final Map<String, String> TICKER_SYMBOL = Map.of(
            "AAPL", "AAPL",
            "TSLA", "TSLA",
            "BTC/USD", "BTC",
            "GOLD", "OR",
            "WTI", "PÉTROLE",
            "EUR/USD", "EUR/USD");

    /** Au-dela de cet ecart entre le prix courant et le cours reel, le prix est recale (et l'ouverture du jour aussi). */
    static final double REANCHOR_JUMP = 0.15;

    private final AssetRepository assets;
    private final AssetPriceTickRepository ticks;
    private final MarketTickerService ticker;
    private final MarketEngineProperties props;
    private final Random random;
    private final ZoneId zone;

    /** Etat par symbole (ecart a l'ancre, ouverture du jour, dernier point d'historique). */
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private ScheduledExecutorService engine;
    private ScheduledExecutorService quotes;

    static final class State {
        double deviation;
        double dayOpen;
        LocalDate day;
        Instant lastHistory;
        boolean anchored;
    }

    /** Un pas pour un actif. */
    public record StepResult(String symbol, BigDecimal price, double changePct, boolean anchored) {
    }

    @Autowired
    public MarketPriceEngine(AssetRepository assets, AssetPriceTickRepository ticks, MarketTickerService ticker,
                             MarketEngineProperties props) {
        this(assets, ticks, ticker, props, new Random(), ZoneId.systemDefault());
    }

    /** Constructeur des tests (aleatoire et fuseau maitrises). */
    public MarketPriceEngine(AssetRepository assets, AssetPriceTickRepository ticks, MarketTickerService ticker,
                      MarketEngineProperties props, Random random, ZoneId zone) {
        this.assets = assets;
        this.ticks = ticks;
        this.ticker = ticker;
        this.props = props;
        this.random = random;
        this.zone = zone;
    }

    @PostConstruct
    void start() {
        if (!props.isEnabled()) {
            log.info("Moteur de prix desactive (market.engine.enabled=false) : prix figes");
            return;
        }
        engine = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "market-engine"));
        long interval = Math.max(200, props.getIntervalMs());
        engine.scheduleWithFixedDelay(this::safeStep, props.getInitialDelayMs(), interval, TimeUnit.MILLISECONDS);
        if (props.isRefreshQuotes()) {
            quotes = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "market-quotes"));
            quotes.scheduleWithFixedDelay(this::refreshQuotes, 0, Math.max(10_000, props.getRefreshQuotesMs()),
                    TimeUnit.MILLISECONDS);
        }
        log.info("Moteur de prix demarre (un pas toutes les {} ms, acceleration ×{})", interval, props.getSpeed());
    }

    @PreDestroy
    void stop() {
        if (engine != null) engine.shutdownNow();
        if (quotes != null) quotes.shutdownNow();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Thread market-quotes : le seul endroit ou l'on attend le reseau. */
    void refreshQuotes() {
        try {
            ticker.ticker();
        } catch (RuntimeException e) {
            log.debug("Cours reels indisponibles ({})", e.getMessage());
        }
    }

    private void safeStep() {
        try {
            step(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Moteur de prix : pas impossible ({})", e.getMessage());
        }
    }

    /** Un pas pour tous les actifs. Public pour les tests et pour un pas a la demande. */
    public List<StepResult> step(Instant now) {
        Map<String, Quote> real = recentQuotes(now);
        double dt = PriceSteps.dtYears(props.getIntervalMs(), props.getSpeed());
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<StepResult> out = new java.util.ArrayList<>();
        Instant purgeBefore = null;
        for (Asset a : assets.findAll()) {
            if (a.getSymbol() == null || a.getType() == null) continue;
            String symbol = a.getSymbol();
            double current = a.getPrice() == null || a.getPrice().signum() <= 0 ? 0 : a.getPrice().doubleValue();
            double sigma = AssetDynamics.of(a.getType()).sigma();
            State st = states.computeIfAbsent(symbol, k -> new State());
            Quote q = real.get(tickerSymbol(symbol));

            double next;
            boolean anchored = q != null && q.price() > 0;
            if (anchored) {
                double anchor = q.price();
                if (current <= 0 || Math.abs(anchor / current - 1) > REANCHOR_JUMP) {
                    // premier ancrage (prix de demo tres different du vrai cours) : recalage franc
                    if (current > 0) {
                        log.info("Moteur de prix : {} recale sur le cours reel {} (etait {})", symbol, anchor, current);
                    }
                    st.deviation = 0;
                    st.day = null;
                } else if (!st.anchored) {
                    // on reprend l'ancrage la ou le prix simule se trouve, sans saut
                    st.deviation = clampDeviation(Math.log(current / anchor));
                }
                st.deviation = PriceSteps.deviation(st.deviation, sigma, dt, props.getReversion(),
                        props.getMaxDeviation(), random.nextGaussian());
                next = PriceSteps.anchored(anchor, st.deviation);
            } else {
                next = PriceSteps.gbm(current > 0 ? current : 1, sigma, dt, random.nextGaussian());
            }
            st.anchored = anchored;

            if (st.day == null || !st.day.equals(today)) {
                st.day = today;
                st.dayOpen = openOfDay(symbol, today, next);
            }
            BigDecimal price = BigDecimal.valueOf(next).setScale(6, RoundingMode.HALF_UP);
            if (price.signum() <= 0) price = BigDecimal.valueOf(PriceSteps.MIN_PRICE).setScale(6, RoundingMode.HALF_UP);
            double change = PriceSteps.changePct(price.doubleValue(), st.dayOpen);
            assets.updateQuote(a.getId(), price, change);
            a.setPrice(price);
            a.setChangePct(change);

            if (st.lastHistory == null || Duration.between(st.lastHistory, now).toMillis() >= props.getHistoryEveryMs()) {
                ticks.save(AssetPriceTick.of(symbol, price, now, anchored));
                st.lastHistory = now;
                purgeBefore = now.minus(Duration.ofDays(Math.max(1, props.getRetentionDays())));
            }
            out.add(new StepResult(symbol, price, change, anchored));
        }
        if (purgeBefore != null) {
            int purged = ticks.purgeBefore(purgeBefore);
            if (purged > 0) log.debug("Historique des cotations : {} point(s) purge(s)", purged);
        }
        return out;
    }

    private double clampDeviation(double x) {
        double b = Math.abs(props.getMaxDeviation());
        return Double.isFinite(x) ? Math.max(-b, Math.min(b, x)) : 0;
    }

    /** Ouverture du jour : premier point d'historique du jour s'il existe (redemarrage), sinon le prix courant. */
    private double openOfDay(String symbol, LocalDate day, double fallback) {
        try {
            return ticks.findFirstBySymbolAndAtGreaterThanEqualOrderByAtAsc(symbol, day.atStartOfDay(zone).toInstant())
                    .map(t -> t.getPrice().doubleValue())
                    .filter(p -> p > 0 && Math.abs(p / fallback - 1) <= REANCHOR_JUMP)
                    .orElse(fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Cours reels du cache du bandeau, s'ils sont assez recents (aucun appel reseau). */
    Map<String, Quote> recentQuotes(Instant now) {
        Ticker t = ticker.cachedTicker();
        if (t == null || t.updatedAt() == null || t.quotes() == null) return Map.of();
        if (Duration.between(t.updatedAt(), now).toMillis() > props.getAnchorMaxAgeMs()) return Map.of();
        Map<String, Quote> map = new HashMap<>();
        for (Quote q : t.quotes()) map.put(q.symbol().toUpperCase(Locale.ROOT), q);
        return map;
    }

    public static String tickerSymbol(String assetSymbol) {
        String s = assetSymbol.trim().toUpperCase(Locale.ROOT);
        return TICKER_SYMBOL.getOrDefault(s, s);
    }

    /** Symbole du bandeau → symbole de l'actif (pour afficher dans le bandeau le prix du moteur). */
    public static Map<String, String> assetSymbolByTicker() {
        Map<String, String> m = new LinkedHashMap<>();
        TICKER_SYMBOL.forEach((asset, tick) -> m.put(tick.toUpperCase(Locale.ROOT), asset));
        return m;
    }
}
