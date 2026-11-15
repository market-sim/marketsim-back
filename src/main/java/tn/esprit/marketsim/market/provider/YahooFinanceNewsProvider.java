package tn.esprit.marketsim.market.provider;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import lombok.extern.slf4j.Slf4j;

/**
 * Fournisseur principal d'actualités financières via le flux RSS de Yahoo Finance.
 *
 * Endpoint : https://feeds.finance.yahoo.com/rss/2.0/headline?s={symbol}
 * Implémente des timeouts stricts, un parsing XML défensif et une tolérance totale aux pannes.
 */
@Slf4j
@Component
public class YahooFinanceNewsProvider implements FinancialNewsProvider {

    public static final String PROVIDER_NAME = "YAHOO_FINANCE";
    private static final String BASE_URL = "https://feeds.finance.yahoo.com/rss/2.0/headline?s=";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;

    public YahooFinanceNewsProvider() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Constructeur injectable pour les tests unitaires. */
    public YahooFinanceNewsProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<FinancialNewsItem> fetchNews(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }

        String querySymbol = toYahooSymbol(symbol.trim().toUpperCase());
        String url = BASE_URL + URLEncoder.encode(querySymbol, StandardCharsets.UTF_8);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MarketSim/1.0")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
                log.warn("Yahoo Finance RSS a répondu HTTP {} pour {}", response.statusCode(), symbol);
                return List.of();
            }

            return parseRss(response.body(), symbol);
        } catch (Exception e) {
            log.warn("Échec de récupération des actualités Yahoo Finance pour {} : {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * Analyse défensive du document XML RSS 2.0.
     */
    public List<FinancialNewsItem> parseRss(byte[] xmlBytes, String originalSymbol) {
        List<FinancialNewsItem> items = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                dbf.setXIncludeAware(false);
                dbf.setExpandEntityReferences(false);
            } catch (Exception ignored) { /* XML parser sans certaines options : le parseur reste isolé */ }

            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xmlBytes));
            doc.getDocumentElement().normalize();

            NodeList itemNodes = doc.getElementsByTagName("item");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                if (itemNodes.item(i) instanceof Element el) {
                    String title = getTagValue(el, "title");
                    String link = getTagValue(el, "link");
                    String pubDateStr = getTagValue(el, "pubDate");
                    String source = getTagValue(el, "source");

                    if (title == null || title.isBlank()) {
                        continue;
                    }

                    if (source == null || source.isBlank()) {
                        source = "Yahoo Finance";
                    }

                    Instant publishedAt = parseDate(pubDateStr);
                    items.add(new FinancialNewsItem(
                            title.trim(),
                            source.trim(),
                            link != null ? link.trim() : null,
                            originalSymbol.toUpperCase(),
                            publishedAt,
                            PROVIDER_NAME
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Erreur de parsing XML pour le flux RSS Yahoo ({}) : {}", originalSymbol, e.getMessage());
        }
        return items;
    }

    private String getTagValue(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl != null && nl.getLength() > 0 && nl.item(0) != null) {
            return nl.item(0).getTextContent();
        }
        return null;
    }

    public static Instant parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        String cleaned = raw.trim();
        try {
            return ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e1) {
            try {
                return Instant.parse(cleaned);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** Convertit les symboles de notation interne MarketSim vers les tickers Yahoo Finance. */
    public static String toYahooSymbol(String symbol) {
        if (symbol == null) return "";
        return switch (symbol.trim().toUpperCase()) {
            case "BTC/USD" -> "BTC-USD";
            case "EUR/USD" -> "EURUSD=X";
            case "GOLD"    -> "GC=F";
            case "WTI"     -> "CL=F";
            default        -> symbol.trim().toUpperCase();
        };
    }
}
