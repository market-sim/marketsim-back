package tn.esprit.marketsim.agent.assistant;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import lombok.extern.slf4j.Slf4j;

/**
 * Actualites financieres gratuites, sans cle API : flux RSS publics de Google News.
 * Les resultats sont gardes 15 minutes en cache pour ne pas surcharger la source.
 */
@Slf4j
@Service
public class NewsService {

    static final Duration CACHE_TTL = Duration.ofMinutes(15);
    static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final Pattern GENERIC = Pattern.compile("(?i)(en direct|cours actions|actualit[ée] en vid[ée]o|toute l'actualit)");

    /** Requetes predefinies (syntaxe de recherche Google News : "when:3d" = 3 derniers jours). */
    public static final String MARKETS = "bourse OR \"Wall Street\" OR \"CAC 40\" OR \"marchés financiers\" when:3d";
    public static final String TRENDS = "(trading OR bourse OR finance) (\"intelligence artificielle\" OR IA OR crypto OR bitcoin OR régulation OR AMF OR \"banque centrale\") when:7d";

    /** Requete d'actualite propre a chaque actif du simulateur. */
    public static final Map<String, String> ASSET_QUERIES = Map.of(
            "AAPL", "Apple action bourse when:7d",
            "TSLA", "Tesla action bourse when:7d",
            "BTC/USD", "bitcoin cours when:3d",
            "EUR/USD", "euro dollar change when:3d",
            "GOLD", "prix de l'or once when:7d",
            "WTI", "pétrole prix baril when:3d");

    public record NewsItem(String title, String source, String url, ZonedDateTime publishedAt) {
    }

    private record Cached(List<NewsItem> items, Instant at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Titres les plus recents pour une requete (liste vide si la source est injoignable). */
    public List<NewsItem> search(String query, int limit) {
        Cached cached = cache.get(query);
        if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items().stream().limit(limit).toList();
        }
        try {
            String url = "https://news.google.com/rss/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&hl=fr&gl=FR&ceid=FR:fr";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (MarketSim AI educational simulator)")
                    .GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("Actualites : HTTP {} pour « {} »", response.statusCode(), query);
                return List.of();
            }
            List<NewsItem> items = parse(response.body());
            cache.put(query, new Cached(items, Instant.now()));
            return items.stream().limit(limit).toList();
        } catch (Exception e) {
            log.warn("Actualites indisponibles pour « {} » : {}", query, e.getMessage());
            return cached != null ? cached.items().stream().limit(limit).toList() : List.of();
        }
    }

    /** Analyse le flux RSS (parseur durci : DOCTYPE et entites externes interdits). */
    static List<NewsItem> parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml));

        NodeList nodes = doc.getElementsByTagName("item");
        List<NewsItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            String title = text(item, "title");
            String source = text(item, "source");
            String link = text(item, "link");
            ZonedDateTime date = parseDate(text(item, "pubDate"));
            if (title == null || link == null) continue;
            // Google News ajoute " - Source" a la fin du titre
            if (source != null && title.endsWith(" - " + source)) {
                title = title.substring(0, title.length() - source.length() - 3);
            }
            if (title.length() < 25 || GENERIC.matcher(title).find()) continue;
            if (!seen.add(title.toLowerCase())) continue;
            items.add(new NewsItem(title.trim(), source == null ? "" : source, link, date));
        }
        items.sort(Comparator.comparing(NewsItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent();
    }

    private static ZonedDateTime parseDate(String value) {
        if (value == null) return null;
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }
}
