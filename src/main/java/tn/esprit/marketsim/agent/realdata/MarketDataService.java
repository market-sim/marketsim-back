package tn.esprit.marketsim.agent.realdata;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.market.Asset;

/**
 * Historique de cours REELS pour le backtest : telechargement gratuit et sans cle depuis Yahoo Finance
 * (10 ans de cloture journaliere ajustee), mis en cache en base. Si la source est injoignable, le dernier
 * historique en base est reutilise, ou l'utilisateur importe un CSV.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Un historique recent (moins de 12 h) n'est pas retelecharge. */
    static final Duration FRESH_FOR = Duration.ofHours(12);
    static final String RANGE = "10y";

    private final MarketSeriesRepository repository;
    private final AgentLabSupport support;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();

    /** Symbole Yahoo Finance d'un actif du module 5 : EUR/USD -> EURUSD=X, BTC/USD -> BTC-USD, GOLD -> GC=F. */
    static String yahooSymbol(Asset asset) {
        String s = asset.getSymbol().trim().toUpperCase(Locale.ROOT);
        switch (s) {
            case "GOLD":
                return "GC=F";
            case "WTI":
                return "CL=F";
            default:
                break;
        }
        if (s.contains("/")) {
            String[] p = s.split("/", 2);
            return asset.getType() == Asset.Type.CRYPTO ? p[0] + "-" + p[1] : p[0] + p[1] + "=X";
        }
        return s;
    }

    /** Historique de l'actif : cache recent, sinon telechargement, sinon dernier historique connu. */
    public PriceSeries series(String symbol) {
        Asset asset = support.asset(symbol);
        MarketSeries stored = repository.findBySymbol(asset.getSymbol()).orElse(null);
        boolean fresh = stored != null && stored.getFetchedAt() != null
                && stored.getFetchedAt().isAfter(LocalDateTime.now().minus(FRESH_FOR));
        if (fresh || (stored != null && "CSV".equals(stored.getSource()))) {
            return decode(stored);
        }
        try {
            return download(asset);
        } catch (RuntimeException e) {
            if (stored != null) {
                log.warn("Donnees reelles {} : telechargement impossible ({}), historique en cache reutilise",
                        asset.getSymbol(), e.getMessage());
                return decode(stored);
            }
            throw new IllegalArgumentException("Impossible de récupérer l'historique de " + asset.getSymbol()
                    + " (" + e.getMessage() + "). Importez un fichier CSV (Date, Close) depuis l'onglet Données réelles.");
        }
    }

    /** Force le telechargement (remplace un historique importe en CSV). */
    public PriceSeries refresh(String symbol) {
        Asset asset = support.asset(symbol);
        try {
            return download(asset);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Téléchargement impossible : " + e.getMessage());
        }
    }

    /** Remplace l'historique de l'actif par un export CSV de l'utilisateur. */
    public PriceSeries importCsv(String symbol, String csv) {
        Asset asset = support.asset(symbol);
        return save(PriceSeries.fromCsv(asset.getSymbol(), csv));
    }

    private PriceSeries download(Asset asset) {
        String yahoo = yahooSymbol(asset);
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(yahoo, StandardCharsets.UTF_8)
                + "?range=" + RANGE + "&interval=1d&includeAdjustedClose=true";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (MarketSim AI educational simulator)")
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Yahoo Finance a répondu HTTP " + response.statusCode() + " pour " + yahoo);
            }
            PriceSeries series = PriceSeries.fromYahoo(asset.getSymbol(), json.readTree(response.body()));
            if (series.size() < PriceSeries.MIN_POINTS) {
                throw new IllegalStateException("seulement " + series.size() + " jours d'historique pour " + yahoo);
            }
            return save(series);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("téléchargement interrompu");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("source injoignable : " + e.getMessage());
        }
    }

    private PriceSeries save(PriceSeries series) {
        MarketSeries row = repository.findBySymbol(series.symbol()).orElseGet(MarketSeries::new);
        row.setSymbol(series.symbol());
        row.setSource(series.source());
        row.setFirstDate(series.dates()[0]);
        row.setLastDate(series.dates()[series.size() - 1]);
        row.setPoints(series.size());
        row.setDataHash(series.hash());
        row.setFetchedAt(LocalDateTime.now());
        row.setData(series.encode());
        repository.save(row);
        return series;
    }

    private static PriceSeries decode(MarketSeries row) {
        return PriceSeries.decode(row.getSymbol(), row.getSource(), row.getData());
    }
}
