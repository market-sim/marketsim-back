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
 * Fournisseur de secours via le flux RSS de Google News.
 *
 * Utilisé uniquement en cas d'échec ou d'absence d'articles sur Yahoo Finance.
 * Extrait et conserve la source originale (ex : Reuters, Bloomberg, CNBC)
 * ainsi que le lien canonique de l'article.
 */
@Slf4j
@Component
public class GoogleNewsFallbackProvider implements FinancialNewsProvider {

    public static final String PROVIDER_NAME = "GOOGLE_NEWS";
    private static final String BASE_URL = "https://news.google.com/rss/search?q=";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;

    public GoogleNewsFallbackProvider() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Constructeur injectable pour les tests. */
    public GoogleNewsFallbackProvider(HttpClient httpClient) {
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

        try {
            String query = URLEncoder.encode(symbol.trim() + " stock financial market", StandardCharsets.UTF_8);
            String url = BASE_URL + query + "&hl=en-US&gl=US&ceid=US:en";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MarketSim/1.0")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
                log.warn("Google News RSS a répondu HTTP {} pour {}", response.statusCode(), symbol);
                return List.of();
            }

            return parseRss(response.body(), symbol);
        } catch (Exception e) {
            log.warn("Échec du secours Google News pour {} : {}", symbol, e.getMessage());
            return List.of();
        }
    }

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

                    // Google News formatte souvent le titre sous la forme : "Headline - Source Name"
                    String cleanHeadline = title.trim();
                    if (source != null && !source.isBlank()) {
                        String suffix = " - " + source.trim();
                        if (cleanHeadline.endsWith(suffix)) {
                            cleanHeadline = cleanHeadline.substring(0, cleanHeadline.length() - suffix.length()).trim();
                        }
                    } else {
                        // Extraction du nom de source depuis le tiret si balise source absente
                        int dashIdx = cleanHeadline.lastIndexOf(" - ");
                        if (dashIdx > 0 && dashIdx < cleanHeadline.length() - 3) {
                            source = cleanHeadline.substring(dashIdx + 3).trim();
                            cleanHeadline = cleanHeadline.substring(0, dashIdx).trim();
                        } else {
                            source = "Google News";
                        }
                    }

                    Instant publishedAt = parseDate(pubDateStr);
                    items.add(new FinancialNewsItem(
                            cleanHeadline,
                            source.trim(),
                            link != null ? link.trim() : null,
                            originalSymbol.toUpperCase(),
                            publishedAt,
                            PROVIDER_NAME
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Erreur parsing XML Google News ({}) : {}", originalSymbol, e.getMessage());
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
}
