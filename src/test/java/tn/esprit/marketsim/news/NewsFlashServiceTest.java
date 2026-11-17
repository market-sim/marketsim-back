package tn.esprit.marketsim.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.assistant.NewsService;
import tn.esprit.marketsim.agent.assistant.NewsService.NewsItem;
import tn.esprit.marketsim.news.NewsFlashService.Flash;
import tn.esprit.marketsim.news.NewsFlashService.FlashItem;
import tn.esprit.marketsim.news.NewsFlashService.Topic;

/** Flash d'actualites : fusion, tri, dedoublonnage, liens surs, reperes de ton. Sans reseau (source simulee). */
class NewsFlashServiceTest {

    private static NewsItem item(String title, int minutesAgo, String url) {
        return new NewsItem(title, "Source", url, ZonedDateTime.now().minusMinutes(minutesAgo));
    }

    /** Source simulee : renvoie les memes titres pour chaque requete, ajoute un titre propre a la requete. */
    private static NewsService fake(List<NewsItem> fixed) {
        return new NewsService() {
            @Override
            public List<NewsItem> search(String query, int limit) {
                List<NewsItem> out = new ArrayList<>(fixed);
                out.add(item("Titre propre à " + Integer.toHexString(query.hashCode()), 5, "https://example.test/" + query.hashCode()));
                return out.stream().limit(limit).toList();
            }
        };
    }

    @Test
    void worldMergesAllTopicsSortedFromNewestAndDeduplicated() {
        NewsFlashService service = new NewsFlashService(fake(List.of(
                item("Wall Street termine en forte hausse après la Fed", 30, "https://example.test/a"),
                item("Le pétrole recule de 3 % à Londres", 10, "https://example.test/b"))));
        Flash flash = service.flash(Topic.WORLD, 15);

        assertEquals("WORLD", flash.topic());
        assertEquals("Monde", flash.label());
        List<String> titles = flash.items().stream().map(FlashItem::title).toList();
        assertEquals(titles.size(), titles.stream().distinct().count(), "aucun doublon malgre 5 requetes identiques");
        List<Long> ages = flash.items().stream().map(FlashItem::minutesAgo).toList();
        assertTrue(ages.equals(ages.stream().sorted().toList()), "du plus recent au plus ancien : " + ages);
        assertTrue(flash.items().size() >= 5, "les titres propres a chaque theme sont tous presents");
    }

    @Test
    void limitIsRespectedAndCapped() {
        NewsFlashService service = new NewsFlashService(fake(List.of(
                item("Titre numéro un assez long pour passer", 1, "https://example.test/1"),
                item("Titre numéro deux assez long pour passer", 2, "https://example.test/2"),
                item("Titre numéro trois assez long pour passer", 3, "https://example.test/3"))));
        assertEquals(2, service.flash(Topic.CRYPTO, 2).items().size());
        assertTrue(service.flash(Topic.CRYPTO, 500).items().size() <= 15);
        assertEquals(1, service.flash(Topic.CRYPTO, -3).items().size(), "minimum 1");
    }

    @Test
    void unsafeLinksAreDroppedAndMissingDatesAreKept() {
        List<FlashItem> items = NewsFlashService.toFlashItems(List.of(
                item("Lien dangereux qui ne doit jamais sortir", 1, "javascript:alert(1)"),
                item("Lien vide qui ne doit jamais sortir ici", 1, null),
                item("Lien sûr et récent à conserver absolument", 2, "https://example.test/ok"),
                new NewsItem("Sans date mais lien sûr à conserver aussi", "Src", "http://example.test/nodate", null)), 10);
        assertEquals(2, items.size());
        assertEquals("https://example.test/ok", items.get(0).url());
        assertEquals(null, items.get(1).publishedAt(), "sans date : a la fin de la liste");
    }

    @Test
    void minutesAgoAndSentimentAreComputed() {
        FlashItem f = NewsFlashService.toFlashItems(List.of(
                item("Le Nasdaq bondit et signe un nouveau record", 45, "https://example.test/x")), 5).get(0);
        assertTrue(Math.abs(f.minutesAgo() - 45) <= 1);
        assertEquals("POSITIVE", f.sentiment());
        assertEquals("NEGATIVE", NewsFlashService.sentiment("Krach à la Bourse de Tokyo : le Nikkei plonge"));
        assertEquals("NEUTRAL", NewsFlashService.sentiment("La BCE publie son bulletin économique"));
        assertEquals("NEGATIVE", NewsFlashService.sentiment("RÉCESSION : l'inquiétude gagne les marchés"), "accents et majuscules ignores");
        // une hausse des taux n'est pas une bonne nouvelle : jamais « haussier » a cause du mot hausse
        assertEquals("NEUTRAL", NewsFlashService.sentiment("Bank of America prévoit deux nouvelles hausses des taux d'intérêt par la Fed"));
        assertEquals("NEUTRAL", NewsFlashService.sentiment("La Fed relève ses taux directeurs"));
        assertEquals("NEGATIVE", NewsFlashService.sentiment("La Fed relève à nouveau ses taux : risques de récession"));
        assertEquals("POSITIVE", NewsFlashService.sentiment("Wall Street en hausse, le Nasdaq grimpe"), "une hausse de la bourse reste positive");
    }

    @Test
    void topicParsingIsTolerantAndRejectsUnknownNames() {
        assertEquals(Topic.CRYPTO, Topic.parse(" crypto "));
        assertEquals(Topic.WORLD, Topic.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Topic.parse("sport"));
    }
}
