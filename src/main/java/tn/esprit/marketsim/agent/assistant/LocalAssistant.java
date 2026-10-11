package tn.esprit.marketsim.agent.assistant;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.AgentInfo;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.DecisionInfo;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.McInfo;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.Snapshot;
import tn.esprit.marketsim.agent.assistant.AssistantContextBuilder.TournamentInfo;
import tn.esprit.marketsim.agent.assistant.AssistantService.Source;
import tn.esprit.marketsim.agent.assistant.NewsService.NewsItem;

/**
 * Assistant local (sans cle API) : comprend les demandes courantes (briefing, actualites,
 * tendances du trading, decisions des agents, notions de finance) et repond en phrases
 * naturelles a partir des donnees de l'application et des actualites Google News.
 */
@Component
@RequiredArgsConstructor
public class LocalAssistant {

    public record Answer(String text, List<Source> sources, boolean newsUsed) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE);

    private final NewsService news;

    public Answer answer(String question, Snapshot s) {
        String q = normalize(question);

        String concept = detectConcept(q);
        if (concept != null && (asksExplanation(q) || q.split("\\s+").length <= 5)) {
            return new Answer(CONCEPTS.get(concept).explain(s), List.of(), false);
        }
        if (has(q, "briefing", "bilan", "point complet", "resume complet", "chamal", "kol chay", "kolchay", "tout savoir")) {
            return briefing(s);
        }
        if (has(q, "pourquoi", "3lech", "alech", "3leh", "aleh", "raison")) {
            return new Answer(lastDecisionWhy(s), List.of(), false);
        }
        String asset = detectAsset(q);
        if (has(q, "change", "tendance", "evolution", "monde du trading", "tbadel", "tbadlet", "avenir", "futur", "nouveaute du trading", "nouveautes du trading")) {
            return trends(s);
        }
        if (asset != null || has(q, "actu", "news", "nouvelles", "info", "bourse", "marche", "wall street", "cac", "akhbar", "a5bar", "journal", "aujourd")) {
            return marketNews(asset, s);
        }
        if (has(q, "agent", "decision", "quoi de neuf", "jdid", "nouveau", "resultat", "simulation", "monte carlo", "tournoi", "performance", "portefeuille")) {
            return new Answer(agentsSummary(s, true), List.of(), false);
        }
        if (has(q, "merci", "yaatik", "ya3tik", "choukran", "barcha")) {
            return new Answer("Avec plaisir ! Si vous voulez, je peux vous lire les dernières actualités ou faire le point sur vos agents.", List.of(), false);
        }
        if (has(q, "bonjour", "salut", "salam", "aslema", "ahla", "hello", "bonsoir", "ca va", "labes", "cv")) {
            return new Answer(greeting(s) + " " + HELP, List.of(), false);
        }
        if (concept != null) {
            return new Answer(CONCEPTS.get(concept).explain(s), List.of(), false);
        }
        return new Answer("Je n'ai pas bien compris votre demande. " + HELP, List.of(), false);
    }

    static final String HELP = "Je peux vous faire le briefing du jour, vous lire les actualités de la bourse ou d'un actif "
            + "(Apple, Tesla, bitcoin, or, pétrole, euro-dollar), vous dire ce qui change dans le monde du trading, expliquer "
            + "les décisions de vos agents et des notions comme la VaR, le ratio de Sharpe ou le Monte Carlo.";

    // =====================================================================================
    // Briefing
    // =====================================================================================

    Answer briefing(Snapshot s) {
        List<Source> sources = new ArrayList<>();
        StringBuilder sb = new StringBuilder(greeting(s)).append(" Voici votre briefing du ")
                .append(LocalDate.now().format(DAY)).append(".\n\n");

        sb.append("Côté agents. ").append(agentsSummary(s, false)).append("\n\n");

        List<NewsItem> markets = news.search(NewsService.MARKETS, 4);
        if (markets.isEmpty()) {
            sb.append("Côté marchés, je n'arrive pas à joindre la source d'actualités pour le moment.\n\n");
        } else {
            sb.append("Côté marchés, voici les titres les plus récents :\n");
            for (NewsItem n : markets) {
                sb.append("- ").append(n.title()).append(" (").append(n.source()).append(", ").append(when(n.publishedAt())).append(")\n");
                sources.add(new Source(n.title(), n.url()));
            }
            sb.append(tone(markets)).append("\n\n");
        }

        Trend trend = trendSummary(2);
        if (trend != null) {
            sb.append("Ce qui change dans le monde du trading : ").append(trend.text()).append("\n\n");
            sources.addAll(trend.sources());
        }

        sb.append("Mon conseil pour la suite : ").append(tip(s));
        return new Answer(sb.toString().trim(), sources, !markets.isEmpty() || trend != null);
    }

    private String tip(Snapshot s) {
        if (s.agents().isEmpty()) {
            return "créez votre premier agent en choisissant une personnalité, puis lancez une simulation Monte Carlo pour voir comment il se comporte.";
        }
        for (AgentInfo a : s.agents()) {
            if (a.strategy() == AgentStrategy.Q_LEARNING && !a.trained()) {
                return a.name() + " utilise l'apprentissage par renforcement mais n'est pas encore entraîné : lancez l'entraînement avant de le faire décider.";
            }
        }
        for (AgentInfo a : s.agents()) {
            if (a.active() && a.riskLevel() >= 80) {
                return a.name() + " est agressif et actif, ses décisions deviennent de vrais ordres. Testez-le avec le scénario de krach à moins 20 % pour voir s'il résiste.";
            }
        }
        if (s.tournament() == null) {
            return "lancez un tournoi entre vos agents : c'est le seul moyen de savoir, tests statistiques à l'appui, lequel est vraiment le meilleur.";
        }
        if (s.decisions().stream().noneMatch(d -> d.rating() != null)) {
            return "notez les décisions de vos agents avec le pouce levé ou baissé, cela calcule votre taux d'approbation humaine.";
        }
        return "essayez le mode replay et mesurez-vous à votre agent sur un marché accéléré, avec un krach surprise.";
    }

    // =====================================================================================
    // Agents
    // =====================================================================================

    String agentsSummary(Snapshot s, boolean withIntro) {
        if (s.agents().isEmpty()) {
            return "Vous n'avez pas encore d'agent. Créez-en un dans cet écran : il analysera le marché, expliquera chaque décision et pourra apprendre tout seul.";
        }
        StringBuilder sb = new StringBuilder();
        if (withIntro) {
            sb.append(s.agents().size() == 1 ? "Vous avez un agent. " : "Vous avez " + s.agents().size() + " agents. ");
        }
        for (AgentInfo a : s.agents().stream().limit(3).toList()) {
            sb.append(a.name()).append(", un agent ").append(strategy(a.strategy())).append(' ').append(personality(a.riskLevel()))
                    .append(" (risque ").append(a.riskLevel()).append(" sur 100), ")
                    .append(a.active() ? "actif : ses décisions deviennent de vrais ordres" : "en pause")
                    .append(a.trained() ? ", entraîné sur " + a.episodes() + " épisodes" : "").append(". ");
            McInfo mc = a.lastMonteCarlo();
            if (mc != null) {
                sb.append("Sa dernière simulation Monte Carlo sur ").append(mc.symbol())
                        .append(mc.crashPct() != null ? ", avec un krach de moins " + mc.crashPct().intValue() + " %," : "")
                        .append(" donne ").append(pct(mc.meanReturn(), true)).append(" en moyenne contre ")
                        .append(pct(mc.benchmarkReturn(), true)).append(" pour le buy and hold. ")
                        .append(mc.meanReturn() > mc.benchmarkReturn() ? "Il fait donc mieux que le marché. " : "Il fait moins bien que le simple achat-conservation sur ce scénario. ")
                        .append("Sa VaR à 95 % est de ").append(pct(mc.var95(), false))
                        .append(" et son drawdown moyen de ").append(pct(-mc.meanDrawdown(), false)).append(". ");
            }
            s.decisions().stream().filter(d -> d.agent().equals(a.name())).findFirst().ifPresent(d ->
                    sb.append("Sa dernière décision : ").append(verb(d.action())).append(' ').append(d.symbol())
                            .append(" avec une confiance de ").append(Math.round(d.confidence() * 100)).append(" %. "));
        }
        TournamentInfo t = s.tournament();
        if (t != null) {
            sb.append("Au dernier tournoi sur ").append(t.symbol()).append(", le classement est : ")
                    .append(t.ranking().replace(" > ", ", puis ")).append(". ").append(t.conclusion());
        }
        long rated = s.decisions().stream().filter(d -> d.rating() != null).count();
        if (rated > 0) {
            long approved = s.decisions().stream().filter(d -> d.rating() != null && d.rating() >= 4).count();
            sb.append(" Vous avez approuvé ").append(approved).append(" des ").append(rated).append(" décisions notées récemment.");
        }
        return sb.toString().trim();
    }

    String lastDecisionWhy(Snapshot s) {
        if (s.decisions().isEmpty()) {
            return "Vos agents n'ont encore pris aucune décision. Ouvrez l'onglet Décisions et cliquez sur « Analyser le marché ».";
        }
        DecisionInfo d = s.decisions().get(0);
        boolean q = s.agents().stream().anyMatch(a -> a.name().equals(d.agent()) && a.strategy() == AgentStrategy.Q_LEARNING);
        StringBuilder sb = new StringBuilder("La dernière décision de ").append(d.agent()).append(" est ")
                .append(verb(d.action())).append(' ').append(d.symbol()).append(", avec une confiance de ")
                .append(Math.round(d.confidence() * 100)).append(" %. ");
        String explanation = d.explanation() == null ? "" : d.explanation();
        int colon = explanation.lastIndexOf(':');
        if (colon >= 0 && colon < explanation.length() - 1) {
            String factors = explanation.substring(colon + 1).replace(".", "").trim();
            sb.append("Les facteurs qui ont le plus pesé sont : ").append(factors).append(". ");
            sb.append(q ? "Un chiffre positif signifie que le facteur soutient la décision choisie, un chiffre négatif qu'il s'y opposait. "
                    : "Un chiffre positif pousse vers l'achat, un chiffre négatif vers la vente : ce sont les valeurs SHAP du modèle. ");
        }
        if (d.confidence() < 0.5) {
            sb.append("Notez que la confiance est faible : l'agent hésitait entre plusieurs actions. ");
        }
        sb.append("Vous pouvez juger cette décision avec le pouce levé ou baissé dans l'onglet Décisions.");
        return sb.toString();
    }

    // =====================================================================================
    // Actualites
    // =====================================================================================

    Answer marketNews(String asset, Snapshot s) {
        String query = asset == null ? NewsService.MARKETS : NewsService.ASSET_QUERIES.get(asset);
        List<NewsItem> items = news.search(query, 5);
        if (items.isEmpty()) {
            return new Answer("Je n'arrive pas à joindre la source d'actualités pour le moment. Vérifiez la connexion internet du serveur et réessayez dans un instant.", List.of(), false);
        }
        StringBuilder sb = new StringBuilder(asset == null
                ? "Voici les actualités les plus récentes de la bourse et des marchés :\n"
                : "Voici les dernières actualités sur " + assetName(asset) + " :\n");
        List<Source> sources = new ArrayList<>();
        for (NewsItem n : items) {
            sb.append("- ").append(n.title()).append(" (").append(n.source()).append(", ").append(when(n.publishedAt())).append(")\n");
            sources.add(new Source(n.title(), n.url()));
        }
        sb.append(tone(items));
        if (asset != null) {
            s.agents().stream().filter(a -> a.assets() != null && a.assets().contains(asset)).findFirst().ifPresent(a -> {
                sb.append(" Votre agent ").append(a.name()).append(" trade cet actif");
                s.decisions().stream().filter(d -> d.agent().equals(a.name()) && d.symbol().equals(asset)).findFirst()
                        .ifPresentOrElse(
                                d -> sb.append(" : sa dernière décision était ").append(noun(d.action())).append(", avec une confiance de ")
                                        .append(Math.round(d.confidence() * 100)).append(" %."),
                                () -> sb.append("."));
                sb.append(" Attention : l'agent n'utilise pas ces actualités, il ne regarde que les prix.");
            });
        }
        return new Answer(sb.toString().trim(), sources, true);
    }

    private record Trend(String text, List<Source> sources) {
    }

    Answer trends(Snapshot s) {
        Trend trend = trendSummary(3);
        if (trend == null) {
            return new Answer("Je n'arrive pas à joindre la source d'actualités pour le moment, réessayez dans un instant.", List.of(), false);
        }
        String text = "Ce qui change en ce moment dans le monde du trading, d'après les articles de la semaine : " + trend.text()
                + "\n\nVotre module suit d'ailleurs ces tendances : un agent d'intelligence artificielle qui apprend seul, explique ses décisions et dont les performances sont vérifiées statistiquement.";
        return new Answer(text, trend.sources(), true);
    }

    /** Regroupe les titres de la semaine par grand theme et cite un exemple par theme. */
    private Trend trendSummary(int maxThemes) {
        List<NewsItem> items = news.search(NewsService.TRENDS, 30);
        if (items.isEmpty()) return null;
        Map<String, List<NewsItem>> byTheme = new LinkedHashMap<>();
        for (NewsItem n : items) {
            String t = normalize(n.title());
            for (Map.Entry<String, Pattern> theme : THEMES.entrySet()) {
                if (theme.getValue().matcher(t).find()) {
                    byTheme.computeIfAbsent(theme.getKey(), k -> new ArrayList<>()).add(n);
                    break;
                }
            }
        }
        if (byTheme.isEmpty()) return null;
        List<Map.Entry<String, List<NewsItem>>> ranked = byTheme.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size()).limit(maxThemes).toList();
        StringBuilder sb = new StringBuilder();
        List<Source> sources = new ArrayList<>();
        String[] order = {"d'abord ", "Ensuite, ", "Et enfin, "};
        for (int i = 0; i < ranked.size(); i++) {
            NewsItem example = ranked.get(i).getValue().get(0);
            sb.append(order[Math.min(i, 2)]).append(ranked.get(i).getKey())
                    .append(" (").append(ranked.get(i).getValue().size()).append(ranked.get(i).getValue().size() > 1 ? " articles" : " article")
                    .append("), par exemple : « ").append(example.title()).append(" ». ");
            sources.add(new Source(example.title(), example.url()));
        }
        return new Trend(sb.toString().trim(), sources);
    }

    static final Map<String, Pattern> THEMES = new LinkedHashMap<>();
    static {
        THEMES.put("l'intelligence artificielle et le trading algorithmique", Pattern.compile("intelligence artificielle|\\bia\\b|algorithm|\\bai\\b|robot"));
        THEMES.put("les cryptomonnaies", Pattern.compile("crypto|bitcoin|ethereum|stablecoin|\\betf\\b"));
        THEMES.put("la régulation des marchés", Pattern.compile("regulat|\\bamf\\b|\\bsec\\b|mica|autorite|sanction|loi\\b|reglement"));
        THEMES.put("les taux et les banques centrales", Pattern.compile("taux|\\bbce\\b|\\bfed\\b|banque centrale|inflation"));
        THEMES.put("la tokenisation et la blockchain", Pattern.compile("tokeni|blockchain"));
        THEMES.put("les géants de la bourse", Pattern.compile("nvidia|apple|tesla|microsoft|amazon|cac 40|wall street|nasdaq"));
    }

    /** Tonalite generale des titres (lexique simple de mots positifs / negatifs). */
    static String tone(List<NewsItem> items) {
        int pos = 0;
        int neg = 0;
        for (NewsItem n : items) {
            String t = normalize(n.title());
            if (POSITIVE.matcher(t).find()) pos++;
            if (NEGATIVE.matcher(t).find()) neg++;
        }
        if (pos == 0 && neg == 0) return "La tonalité de ces titres est plutôt neutre.";
        if (pos > neg) return "La tonalité générale est plutôt positive (" + count(pos, "titre optimiste", "titres optimistes") + " contre " + neg + " pessimiste" + (neg > 1 ? "s" : "") + ").";
        if (neg > pos) return "La tonalité générale est plutôt négative (" + count(neg, "titre pessimiste", "titres pessimistes") + " contre " + pos + " optimiste" + (pos > 1 ? "s" : "") + ").";
        return "La tonalité est partagée, autant de titres optimistes que pessimistes.";
    }

    private static String count(int n, String singular, String plural) {
        return n + " " + (n > 1 ? plural : singular);
    }

    static final Pattern POSITIVE = Pattern.compile("haussier|hausse|rebond|record|progress|gagne|bondit|grimpe|optimis|envol|sommet|soutien|accord|rallye|croissance");
    static final Pattern NEGATIVE = Pattern.compile("baissier|baisse|chute|recul|krach|inquiet|crise|plonge|perte|recession|tension|menace|fraude|faillite|degringol|peur");

    // =====================================================================================
    // Notions de finance (personnalisees avec les chiffres de l'utilisateur)
    // =====================================================================================

    @FunctionalInterface
    interface Concept {
        String explain(Snapshot s);
    }

    static final Map<String, Pattern> CONCEPT_PATTERNS = new LinkedHashMap<>();
    static final Map<String, Concept> CONCEPTS = new LinkedHashMap<>();

    private static void concept(String key, String pattern, Concept c) {
        CONCEPT_PATTERNS.put(key, Pattern.compile(pattern));
        CONCEPTS.put(key, c);
    }

    static {
        concept("cvar", "cvar|expected shortfall|perte moyenne extreme", s -> {
            McInfo mc = firstMc(s);
            return "La CVaR, ou Expected Shortfall, répond à la question : quand ça tourne mal, combien je perds en moyenne ? "
                    + "On prend les 5 % de pires scénarios et on fait la moyenne de leurs pertes. Elle est toujours au moins égale à la VaR, "
                    + "et elle voit les krachs que la VaR ignore."
                    + (mc == null ? "" : " Dans votre dernière simulation sur " + mc.symbol() + ", la CVaR à 95 % est de "
                    + pct(mc.cvar95(), false) + " : dans les 5 % de pires cas, la perte moyenne est de " + pct(mc.cvar95(), false) + ".");
        });
        concept("var", "\\bvar\\b|value at risk|valeur a risque", s -> {
            McInfo mc = firstMc(s);
            return "La VaR, ou Value at Risk, c'est la perte qu'on ne dépasse que rarement. Une VaR à 95 % de 20 % veut dire : "
                    + "dans 95 % des cas, on perd moins de 20 %, et seulement dans 5 % des cas on perd davantage. "
                    + "On la calcule ici avec des milliers de scénarios Monte Carlo."
                    + (mc == null ? " Lancez une simulation Monte Carlo et je vous donnerai la VaR de votre agent."
                    : " Pour votre dernière simulation sur " + mc.symbol() + ", la VaR à 95 % est de " + pct(mc.var95(), false)
                    + " : dans 95 % des scénarios, votre agent perd moins que cela.");
        });
        concept("sharpe", "sharpe", s -> {
            McInfo mc = firstMc(s);
            return "Le ratio de Sharpe mesure la performance par unité de risque : le rendement moyen divisé par la volatilité. "
                    + "Gagner 10 % en tremblant beaucoup vaut moins que gagner 8 % tranquillement. Au-dessus de 1, c'est très bon ; "
                    + "autour de 0,3, c'est le niveau typique d'une action."
                    + (mc == null ? "" : " Votre agent a un Sharpe moyen de " + String.format(Locale.FRANCE, "%.2f", mc.sharpe())
                    + " sur sa dernière simulation.") + " C'est le critère qui sert à classer les agents dans le tournoi.";
        });
        concept("drawdown", "drawdown|perte max|recul max", s -> {
            McInfo mc = firstMc(s);
            return "Le drawdown, c'est la chute depuis le plus haut atteint. Si le portefeuille monte à 120 puis redescend à 90, "
                    + "le drawdown est de 25 %. C'est ce que ressent un investisseur : la douleur de voir fondre ses gains."
                    + (mc == null ? "" : " Le drawdown moyen de votre agent est de " + pct(-mc.meanDrawdown(), false) + ".");
        });
        concept("montecarlo", "monte carlo|montecarlo|trajectoire|simulation", s -> {
            McInfo mc = firstMc(s);
            return "La simulation Monte Carlo consiste à imaginer des milliers de futurs possibles pour le prix, avec un modèle "
                    + "mathématique comme le mouvement brownien ou les changements de régimes, puis à faire trader l'agent sur chacun. "
                    + "On obtient non pas un seul résultat, mais toute une distribution : rendement moyen, risque, pire cas. "
                    + "Les variables antithétiques divisent l'erreur de calcul en simulant chaque futur et son miroir."
                    + (mc == null ? "" : " Votre dernière simulation a utilisé " + mc.paths() + " trajectoires sur " + mc.symbol() + ".");
        });
        concept("qlearning", "q-learning|q learning|qlearning|renforcement|\\brl\\b|apprentissage", s ->
                "Le Q-learning est un apprentissage par renforcement : l'agent joue des milliers d'années de marché simulé. "
                        + "Dans chaque situation, il essaie une action et reçoit une récompense, le gain moins les frais et une pénalité de risque. "
                        + "Petit à petit, il remplit un tableau, la Q-table, qui dit quelle action rapporte le plus dans chacune des 36 situations. "
                        + "Au début il explore au hasard, puis il exploite ce qu'il a appris."
                        + s.agents().stream().filter(AgentInfo::trained).findFirst()
                        .map(a -> " Votre agent " + a.name() + " a été entraîné sur " + a.episodes() + " épisodes.").orElse(""));
        concept("shap", "shap|xai|explicab|contribution", s ->
                "SHAP sert à expliquer une décision d'IA : il calcule combien chaque indicateur a poussé vers l'achat ou la vente. "
                        + "Pour nos agents linéaires, ce sont des valeurs SHAP exactes, et leur somme donne le score de la décision. "
                        + "C'est ce que montrent les barres vertes et rouges sous chaque décision.");
        concept("stoploss", "stop.?loss|take.?profit", s ->
                "Le stop-loss est un ordre de sortie automatique si le prix baisse trop, pour limiter la perte. Le take-profit fait l'inverse : "
                        + "il encaisse le gain quand le prix a assez monté. Ici, ils sont calculés selon la volatilité, avec un gain visé deux fois "
                        + "plus grand que le risque pris.");
        concept("momentum", "momentum", s ->
                "Le momentum, c'est l'idée que ce qui monte a tendance à continuer de monter pendant un moment. On le mesure ici avec le "
                        + "rendement des 20 derniers jours, rapporté à la volatilité.");
        concept("rsi", "\\brsi\\b|surachat|survente", s ->
                "Le RSI est un indicateur entre 0 et 100. Au-dessus de 70, l'actif est dit suracheté, il a beaucoup monté ; "
                        + "en dessous de 30, il est survendu. Les stratégies de retour à la moyenne s'en servent pour acheter les excès de baisse.");
        concept("tournoi", "tournoi|p.?value|significati|holm|bootstrap", s ->
                "Le tournoi fait trader tous vos agents sur exactement les mêmes marchés simulés, puis compare chaque paire avec des tests "
                        + "statistiques. La p-value dit si l'écart peut être dû au hasard : en dessous de 5 %, la différence est jugée significative. "
                        + "La correction de Holm évite de trouver de fausses différences quand on fait beaucoup de tests."
                        + (s.tournament() == null ? "" : " Au dernier tournoi : " + s.tournament().conclusion()));
        concept("krach", "krach|crash|stress", s ->
                "Le scénario de krach impose une chute brutale à toutes les trajectoires, par exemple moins 20 % en un jour, "
                        + "suivie de plusieurs semaines de panique où la volatilité double. On voit ainsi si l'agent limite la casse "
                        + "grâce à son stop-loss et à sa lecture de la tendance.");
        concept("volatilite", "volatil", s ->
                "La volatilité mesure l'ampleur des variations du prix. Une action tourne autour de 30 % par an, le bitcoin autour de 70 %. "
                        + "Plus elle est forte, plus les gains et les pertes possibles sont grands.");
        concept("buyhold", "buy.?(and|&)?.?hold|achat.?conservation", s ->
                "Le buy and hold, c'est acheter et garder sans rien faire. C'est la référence à battre : si un agent ne fait pas mieux, "
                        + "en rendement ou en risque, autant ne pas trader.");
    }

    // =====================================================================================
    // Outils
    // =====================================================================================

    static String normalize(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    private static boolean has(String q, String... words) {
        for (String w : words) if (q.contains(w)) return true;
        return false;
    }

    private static boolean asksExplanation(String q) {
        return has(q, "c'est quoi", "cest quoi", "qu'est", "quest-ce", "explique", "definition", "chnowa", "chniya", "chnia",
                "ya3ni", "yaani", "fasar", "fassar", "comment marche", "comment fonctionne", "signifie", "veut dire", "ca sert");
    }

    static String detectConcept(String q) {
        for (Map.Entry<String, Pattern> e : CONCEPT_PATTERNS.entrySet()) {
            if (e.getValue().matcher(q).find()) return e.getKey();
        }
        return null;
    }

    static String detectAsset(String q) {
        if (has(q, "apple", "aapl", "iphone")) return "AAPL";
        if (has(q, "tesla", "tsla", "musk")) return "TSLA";
        if (has(q, "bitcoin", "btc", "crypto")) return "BTC/USD";
        if (has(q, "eur/usd", "euro", "dollar", "forex", "devise")) return "EUR/USD";
        if (has(q, "gold", "l'or", "dhab", "metal precieux", "metaux precieux")) return "GOLD";
        if (has(q, "petrole", "wti", "baril", "brent")) return "WTI";
        return null;
    }

    private static String assetName(String symbol) {
        return switch (symbol) {
            case "AAPL" -> "Apple";
            case "TSLA" -> "Tesla";
            case "BTC/USD" -> "le bitcoin";
            case "EUR/USD" -> "l'euro face au dollar";
            case "GOLD" -> "l'or";
            case "WTI" -> "le pétrole";
            default -> symbol;
        };
    }

    private static McInfo firstMc(Snapshot s) {
        return s.agents().stream().map(AgentInfo::lastMonteCarlo).filter(m -> m != null).findFirst().orElse(null);
    }

    private static String greeting(Snapshot s) {
        return (LocalTime.now().getHour() >= 18 ? "Bonsoir " : "Bonjour ") + s.userName() + " !";
    }

    private static String strategy(AgentStrategy s) {
        return switch (s) {
            case MOMENTUM -> "de suivi de tendance";
            case MEAN_REVERSION -> "de retour à la moyenne";
            case Q_LEARNING -> "à apprentissage par renforcement";
        };
    }

    private static String personality(int risk) {
        return risk <= 33 ? "prudent" : risk <= 66 ? "équilibré" : "agressif";
    }

    private static String verb(AgentAction a) {
        return switch (a) {
            case BUY -> "acheter";
            case SELL -> "vendre";
            case HOLD -> "attendre sur";
        };
    }

    private static String noun(AgentAction a) {
        return switch (a) {
            case BUY -> "un achat";
            case SELL -> "une vente";
            case HOLD -> "d'attendre";
        };
    }

    /** 0.038 -> "+3,8 %" (signed) ou "3,8 %". */
    static String pct(double fraction, boolean signed) {
        return String.format(Locale.FRANCE, signed ? "%+.1f %%" : "%.1f %%", fraction * 100);
    }

    private static String when(ZonedDateTime date) {
        if (date == null) return "date inconnue";
        long days = ChronoUnit.DAYS.between(date.toLocalDate(), LocalDate.now());
        return days <= 0 ? "aujourd'hui" : days == 1 ? "hier" : "il y a " + days + " jours";
    }
}
