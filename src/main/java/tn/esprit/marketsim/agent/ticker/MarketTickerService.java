package tn.esprit.marketsim.agent.ticker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Bandeau de cotations mondiales (actions, indices, devises, matieres premieres, cryptos)
 * a partir de sources publiques gratuites, sans cle : Yahoo Finance (graphique journalier)
 * et CoinGecko. Les cours sont gardes 60 secondes en cache, partages entre tous les utilisateurs.
 */
@Slf4j
@Service
public class MarketTickerService {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    static final Duration TIMEOUT = Duration.ofSeconds(8);

    public enum Category { INDEX, STOCK, FOREX, COMMODITY, CRYPTO }

    public record Quote(String symbol, String name, Category category, double price, double change,
                        double changePct, String currency) {
    }

    public record Ticker(List<Quote> quotes, Instant updatedAt, boolean stale) {
    }

    private record Instrument(String yahoo, String symbol, String name, Category category) {
    }

    /** Ordre d'affichage dans le bandeau. */
    private static final List<Instrument> YAHOO = List.of(
            new Instrument("^GSPC", "S&P 500", "S&P 500", Category.INDEX),
            new Instrument("^IXIC", "NASDAQ", "Nasdaq", Category.INDEX),
            new Instrument("^DJI", "DOW", "Dow Jones", Category.INDEX),
            new Instrument("^FCHI", "CAC 40", "CAC 40", Category.INDEX),
            new Instrument("^N225", "NIKKEI", "Nikkei 225", Category.INDEX),
            new Instrument("AAPL", "AAPL", "Apple", Category.STOCK),
            new Instrument("MSFT", "MSFT", "Microsoft", Category.STOCK),
            new Instrument("NVDA", "NVDA", "Nvidia", Category.STOCK),
            new Instrument("GOOGL", "GOOGL", "Alphabet", Category.STOCK),
            new Instrument("AMZN", "AMZN", "Amazon", Category.STOCK),
            new Instrument("META", "META", "Meta", Category.STOCK),
            new Instrument("TSLA", "TSLA", "Tesla", Category.STOCK),
            new Instrument("EURUSD=X", "EUR/USD", "Euro / Dollar", Category.FOREX),
            new Instrument("EURTND=X", "EUR/TND", "Euro / Dinar", Category.FOREX),
            new Instrument("USDTND=X", "USD/TND", "Dollar / Dinar", Category.FOREX),
            new Instrument("GC=F", "OR", "Or (once)", Category.COMMODITY),
            new Instrument("CL=F", "PÉTROLE", "Pétrole WTI", Category.COMMODITY));

    private static final Map<String, String[]> COINGECKO = Map.of(
            "bitcoin", new String[] {"BTC", "Bitcoin"},
            "ethereum", new String[] {"ETH", "Ethereum"},
            "solana", new String[] {"SOL", "Solana"});

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile Ticker cache;

    public Ticker ticker() {
        Ticker current = cache;
        if (current != null && current.updatedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return current;
        }
        synchronized (this) {
            if (cache != null && cache.updatedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return cache;
            }
            List<Quote> quotes = fetchAll();
            if (quotes.isEmpty()) {
                // sources injoignables : on garde les derniers cours connus, marques comme anciens
                return cache == null ? new Ticker(List.of(), Instant.now(), true)
                        : new Ticker(cache.quotes(), cache.updatedAt(), true);
            }
            cache = new Ticker(quotes, Instant.now(), false);
            return cache;
        }
    }

    private List<Quote> fetchAll() {
        List<CompletableFuture<Quote>> futures = new ArrayList<>();
        for (Instrument i : YAHOO) futures.add(yahoo(i));
        CompletableFuture<List<Quote>> crypto = coingecko();

        List<Quote> quotes = new ArrayList<>();
        int insertCryptoAt = -1;
        for (int k = 0; k < futures.size(); k++) {
            Quote q = futures.get(k).exceptionally(e -> null).join();
            if (q != null) quotes.add(q);
            if (YAHOO.get(k).category() == Category.STOCK) insertCryptoAt = quotes.size();
        }
        List<Quote> cryptos = crypto.exceptionally(e -> List.of()).join();
        // les cryptos apres les actions
        quotes.addAll(insertCryptoAt < 0 ? quotes.size() : insertCryptoAt, cryptos);
        return quotes;
    }

    private CompletableFuture<Quote> yahoo(Instrument i) {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + java.net.URLEncoder.encode(i.yahoo(), java.nio.charset.StandardCharsets.UTF_8) + "?range=1d&interval=1d";
        return http.sendAsync(request(url), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    if (r.statusCode() != 200) return null;
                    try {
                        JsonNode meta = json.readTree(r.body()).path("chart").path("result").path(0).path("meta");
                        double price = meta.path("regularMarketPrice").asDouble(Double.NaN);
                        double previous = meta.path("chartPreviousClose").asDouble(Double.NaN);
                        if (Double.isNaN(price) || Double.isNaN(previous) || previous == 0) return null;
                        return new Quote(i.symbol(), i.name(), i.category(), price, price - previous,
                                (price / previous - 1) * 100, meta.path("currency").asText("USD"));
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    private CompletableFuture<List<Quote>> coingecko() {
        String url = "https://api.coingecko.com/api/v3/simple/price?ids=" + String.join(",", COINGECKO.keySet())
                + "&vs_currencies=usd&include_24hr_change=true";
        return http.sendAsync(request(url), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    List<Quote> out = new ArrayList<>();
                    if (r.statusCode() != 200) return out;
                    try {
                        JsonNode root = json.readTree(r.body());
                        for (String id : List.of("bitcoin", "ethereum", "solana")) {
                            JsonNode n = root.path(id);
                            if (!n.has("usd")) continue;
                            double price = n.path("usd").asDouble();
                            double pct = n.path("usd_24h_change").asDouble(0);
                            double change = price - price / (1 + pct / 100);
                            String[] label = COINGECKO.get(id);
                            out.add(new Quote(label[0], label[1], Category.CRYPTO, price, change, pct, "USD"));
                        }
                    } catch (Exception e) {
                        log.warn("Ticker : reponse CoinGecko illisible");
                    }
                    return out;
                });
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (MarketSim AI educational simulator)")
                .header("Accept", "application/json").GET().build();
    }
}
