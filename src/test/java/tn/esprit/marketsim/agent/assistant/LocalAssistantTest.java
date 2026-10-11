package tn.esprit.marketsim.agent.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.AgentInfo;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.DecisionInfo;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.McInfo;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.Snapshot;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.TournamentInfo;
import tn.esprit.marketsim.agent.assistant.NewsService.NewsItem;

class LocalAssistantTest {

    /** Source d'actualites factice : pas d'appel reseau pendant les tests. */
    static class FakeNews extends NewsService {
        @Override
        public List<NewsItem> search(String query, int limit) {
            ZonedDateTime now = ZonedDateTime.now();
            return List.of(
                    new NewsItem("Le CAC 40 grimpe à un nouveau record porté par le luxe", "Les Echos", "https://ex.com/1", now),
                    new NewsItem("Bitcoin : la régulation MiCA entre en vigueur en Europe", "BFM", "https://ex.com/2", now.minusDays(1)),
                    new NewsItem("L'intelligence artificielle s'impose dans les salles de marché", "Le Monde", "https://ex.com/3", now.minusDays(2)),
                    new NewsItem("Le pétrole chute sur fond d'inquiétudes pour la demande", "Reuters", "https://ex.com/4", now))
                    .stream().limit(limit).toList();
        }
    }

    private final LocalAssistant assistant = new LocalAssistant(new FakeNews());

    private final Snapshot snapshot = new Snapshot("yasmine", "DEBUTANT",
            List.of(new AgentInfo("yasmine", AgentStrategy.Q_LEARNING, 85, "AAPL", true, true, 2000,
                    new McInfo(LocalDateTime.now(), "AAPL", 2000, 10.0, 0.038, -0.014, 0.262, 0.313, 0.17, -0.198, 0.53))),
            List.of(new DecisionInfo(LocalDateTime.now(), "yasmine", AgentAction.BUY, "AAPL", 0.37, null,
                    "Achat AAPL (confiance 37 %) — principaux facteurs : Position actuelle +0,10.")),
            new TournamentInfo(LocalDateTime.now(), "AAPL", 2000, "yasmine > Buy & Hold", "yasmine",
                    "yasmine obtient le meilleur Sharpe moyen (0,45)."));

    @Test
    void explainsConceptWithUsersOwnNumbers() {
        String text = assistant.answer("C'est quoi la VaR ?", snapshot).text();
        assertTrue(text.contains("Value at Risk"));
        assertTrue(text.contains("26,2 %"), "utilise la VaR de la derniere simulation");
    }

    @Test
    void understandsTunisianDialect() {
        LocalAssistant.Answer news = assistant.answer("a5bar el bourse lyoum", snapshot);
        assertTrue(news.newsUsed());
        assertEquals(4, news.sources().size());

        String why = assistant.answer("3lech chra AAPL ?", snapshot).text();
        assertTrue(why.contains("acheter AAPL"));
        assertTrue(why.contains("confiance est faible"));
    }

    @Test
    void briefingCoversAgentsMarketsTrendsAndTip() {
        LocalAssistant.Answer a = assistant.answer("Fais-moi le briefing du jour", snapshot);
        assertTrue(a.text().contains("Côté agents"));
        assertTrue(a.text().contains("Côté marchés"));
        assertTrue(a.text().contains("Ce qui change dans le monde du trading"));
        assertTrue(a.text().contains("Mon conseil"));
        assertTrue(a.text().contains("krach"), "conseil adapte : agent agressif et actif");
    }

    @Test
    void assetQuestionMentionsTheAgentTradingIt() {
        String text = assistant.answer("Quelles sont les nouvelles sur Apple ?", snapshot).text();
        assertTrue(text.contains("Votre agent yasmine trade cet actif"));
    }

    @Test
    void agentsSummaryIsNaturalLanguage() {
        String text = assistant.answer("Quoi de neuf dans mes agents ?", snapshot).text();
        assertTrue(text.contains("apprentissage par renforcement agressif"));
        assertTrue(text.contains("+3,8 %"));
        assertTrue(text.contains("mieux que le marché"));
    }

    @Test
    void toneLexicon() {
        assertTrue(LocalAssistant.tone(new FakeNews().search("", 4)).contains("partagée")
                || LocalAssistant.tone(new FakeNews().search("", 4)).contains("positive"));
    }

    @Test
    void parsesGoogleNewsRssAndRejectsDoctype() throws Exception {
        String rss = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss><channel>
                <item><title>Wall Street termine en hausse après les résultats de Nvidia - Les Echos</title>
                <link>https://news.example/a</link><pubDate>Fri, 09 Oct 2026 10:00:00 GMT</pubDate>
                <source url="https://lesechos.fr">Les Echos</source></item>
                <item><title>Bourse en direct, Cours actions et dérivés - Boursorama</title>
                <link>https://news.example/b</link><pubDate>Fri, 09 Oct 2026 11:00:00 GMT</pubDate>
                <source url="https://boursorama.com">Boursorama</source></item>
                </channel></rss>""";
        List<NewsItem> items = NewsService.parse(rss.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, items.size(), "page generique filtree");
        assertEquals("Wall Street termine en hausse après les résultats de Nvidia", items.get(0).title());
        assertEquals("Les Echos", items.get(0).source());

        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss>&x;</rss>";
        assertThrows(Exception.class, () -> NewsService.parse(xxe.getBytes(StandardCharsets.UTF_8)));
    }
}
