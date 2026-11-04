package tn.esprit.marketsim.news;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.assistant.LocalAssistant;
import tn.esprit.marketsim.agent.assistant.NewsService;
import tn.esprit.marketsim.agent.assistant.NewsService.NewsItem;

/**
 * Flash d'actualites du trading dans le monde, par theme, a partir des flux publics de Google Actualites
 * (via {@link NewsService} : sans cle, 15 minutes de cache). Les titres sont en francais pour etre lus par la voix du navigateur.
 */
@Service
@RequiredArgsConstructor
public class NewsFlashService {

    /** Themes proposes ; {@code WORLD} reunit tous les autres. */
    public enum Topic {
        WORLD("Monde", null),
        STOCKS("Actions", "(\"Wall Street\" OR \"bourses européennes\" OR \"Bourse de Tokyo\" OR \"Bourse de Paris\" OR Nasdaq) when:2d"),
        CRYPTO("Crypto", "(bitcoin OR ethereum OR crypto-monnaies) when:2d"),
        FOREX("Devises", "(\"euro dollar\" OR \"taux de change\" OR \"marché des changes\" OR \"yen japonais\" OR \"dinar tunisien\") when:3d"),
        COMMODITIES("Matières premières", "(pétrole OR \"baril de Brent\" OR \"prix de l'or\") when:2d"),
        RATES("Banques centrales", "(Fed OR BCE OR \"banque centrale\" OR \"taux directeurs\") when:3d");

        final String label;
        final String query;

        Topic(String label, String query) {
            this.label = label;
            this.query = query;
        }

        public static Topic parse(String value) {
            try {
                return valueOf(value == null ? "WORLD" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Thème d'actualités inconnu : " + value);
            }
        }
    }

    /** @param sentiment POSITIVE, NEGATIVE ou NEUTRAL : simple repere par mots-cles du titre, pas une analyse fine */
    public record FlashItem(String title, String source, String url, String publishedAt, Long minutesAgo, String sentiment) {
    }

    public record Flash(String topic, String label, List<FlashItem> items, String generatedAt) {
    }

    static final int PER_TOPIC_IN_WORLD = 3;
    private static final Pattern RATE_HIKE = Pattern.compile(
            "(hausses?|relev\\w*|augment\\w*)\\s+(?:a nouveau\\s+)?(?:de\\s+|des\\s+|du\\s+|ses\\s+|les\\s+|son\\s+|le\\s+)?taux(?:\\s+(?:d'interet|directeurs?))?");

    private final NewsService news;

    public Flash flash(Topic topic, int limit) {
        int max = Math.max(1, Math.min(15, limit));
        List<NewsItem> items = topic == Topic.WORLD ? world() : news.search(topic.query, max);
        return new Flash(topic.name(), topic.label, toFlashItems(items, max), Instant.now().toString());
    }

    /** Les cinq themes interroges en parallele (chaque requete est mise en cache), fusionnes par date. */
    private List<NewsItem> world() {
        List<CompletableFuture<List<NewsItem>>> calls = new ArrayList<>();
        for (Topic t : Topic.values()) {
            if (t.query != null) calls.add(CompletableFuture.supplyAsync(() -> news.search(t.query, PER_TOPIC_IN_WORLD)));
        }
        List<NewsItem> all = new ArrayList<>();
        for (CompletableFuture<List<NewsItem>> call : calls) all.addAll(call.exceptionally(e -> List.of()).join());
        return all;
    }

    /** Dedoublonne par titre, ecarte les liens non http(s), trie du plus recent au plus ancien. */
    static List<FlashItem> toFlashItems(List<NewsItem> items, int limit) {
        Map<String, NewsItem> unique = new LinkedHashMap<>();
        for (NewsItem n : items) {
            if (n.title() == null || n.url() == null || !n.url().regionMatches(true, 0, "http", 0, 4)) continue;
            unique.putIfAbsent(n.title().toLowerCase(Locale.ROOT), n);
        }
        ZonedDateTime now = ZonedDateTime.now();
        return unique.values().stream()
                .sorted(Comparator.comparing(NewsItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(n -> new FlashItem(n.title(), n.source(), n.url(),
                        n.publishedAt() == null ? null : n.publishedAt().toInstant().toString(),
                        n.publishedAt() == null ? null : Math.max(0, Duration.between(n.publishedAt(), now).toMinutes()),
                        sentiment(n.title())))
                .toList();
    }

    static String sentiment(String title) {
        String t = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        // « hausse des taux » n'est pas une bonne nouvelle pour les marches : on n'en fait pas un signal positif
        t = RATE_HIKE.matcher(t).replaceAll(" ");
        long pos = LocalAssistant.POSITIVE.matcher(t).results().count();   // le plus de mots d'un camp l'emporte
        long neg = LocalAssistant.NEGATIVE.matcher(t).results().count();
        return pos > neg ? "POSITIVE" : neg > pos ? "NEGATIVE" : "NEUTRAL";
    }
}
