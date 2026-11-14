package tn.esprit.marketsim.progression.academy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tn.esprit.marketsim.progression.dto.ProgressionDtos.GameHistoryItem;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.ProgressionResponse;

/**
 * Parcours personnalise de l'Academie (logique pure, testable seule).
 *
 * <p>Lit les vraies statistiques du Jeu de trading et en deduit les stations a travailler en priorite,
 * chacune avec la raison chiffree. Debloque aussi la « Salle des Experts » quand le joueur bat souvent l'agent IA.</p>
 */
public final class AcademyAdvisor {

    private AcademyAdvisor() {
    }

    public enum Priority { HIGH, MEDIUM, LOW }

    /** Une station conseillee et pourquoi. */
    public record Recommendation(String stationId, Priority priority, List<String> reasons) {
    }

    /**
     * @param beatAgent      parties comptees ou le joueur a fait mieux que l'agent
     * @param secretUnlocked vrai a partir de {@link #SECRET_WINS} victoires contre l'agent
     * @param summary        une phrase pour le mentor
     */
    public record Advice(List<Recommendation> recommendations, int games, int beatAgent, int gamesVsAgent,
                         boolean secretUnlocked, String summary) {
    }

    public static final int SECRET_WINS = 3;

    /** Noms des stations (les memes que dans le monde 3D). */
    public static final Map<String, String> STATION_NAMES = Map.of(
            "market", "Place du Marché", "candles", "Tour des Bougies", "orders", "Banque des Ordres",
            "risk", "Forteresse du Risque", "indicators", "Observatoire", "psychology", "Temple du Mental",
            "diversify", "Port des Actifs", "crash", "Volcan du Krach", "experts", "Salle des Experts");
    /** En dessous, les pourcentages ne veulent pas dire grand-chose. */
    static final int MIN_TRADES = 5;

    public static Advice advise(ProgressionResponse p, List<GameHistoryItem> history) {
        List<GameHistoryItem> games = history == null ? List.of() : history;
        Map<String, Recommendation> recos = new LinkedHashMap<>();
        int beat = 0;
        int compared = 0;
        for (GameHistoryItem g : games) {
            if (g.agentReturn() == null || !g.counted()) continue;
            compared++;
            if (g.playerReturn() > g.agentReturn()) beat++;
        }

        if (p == null || p.games() == 0) {
            add(recos, "market", Priority.LOW, "Tu n'as pas encore joué de partie : commence par les bases, puis teste-les dans le Jeu de trading.");
            return new Advice(sorted(recos), 0, beat, compared, beat >= SECRET_WINS,
                    "Pas encore de partie jouée : je suivrai tes résultats dès ta première partie.");
        }

        boolean enough = p.trades() >= MIN_TRADES;
        if (p.bankrupt() || p.restarts() > 0) {
            add(recos, "risk", Priority.HIGH, p.bankrupt()
                    ? "Ton portefeuille est presque vide : la protection du capital est la priorité."
                    : "Tu as déjà dû recommencer " + p.restarts() + " fois : il faut limiter les pertes par trade.");
        }
        if (enough && p.discipline() < 0.6) {
            add(recos, "risk", Priority.HIGH, "Seulement " + pct(p.discipline())
                    + " de tes trades sont fermés avant que la perte dépasse le stop conseillé.");
        }
        if (p.avgMaxDrawdown() > 0.15) {
            add(recos, "crash", Priority.HIGH, "Ta baisse maximale moyenne par partie est de " + pct(p.avgMaxDrawdown())
                    + " : apprends à traverser les chutes sans paniquer.");
        }
        if (enough && p.profitFactor() < 1) {
            add(recos, "psychology", Priority.MEDIUM, "Tes pertes sont plus grosses que tes gains (facteur de profit "
                    + String.format(Locale.FRANCE, "%.2f", p.profitFactor()) + ") : coupe tes pertes, laisse courir tes gains.");
        }
        if (enough && p.winRate() < 0.45) {
            add(recos, "candles", Priority.MEDIUM, "Ton taux de réussite est de " + pct(p.winRate())
                    + " : travaille la lecture de la tendance avant d'entrer.");
            add(recos, "indicators", Priority.LOW, "Les indicateurs (moyenne mobile, RSI) aident à confirmer une entrée.");
        }
        double tradesPerGame = (double) p.trades() / p.games();
        if (tradesPerGame > 8) {
            add(recos, "orders", Priority.MEDIUM, "Tu fais en moyenne " + Math.round(tradesPerGame)
                    + " trades par partie : les frais mangent tes gains.");
        }
        long symbols = games.stream().map(GameHistoryItem::symbol).distinct().count();
        if (games.size() >= 3 && symbols == 1) {
            add(recos, "diversify", Priority.LOW, "Tu joues toujours sur " + games.get(0).symbol()
                    + " : essaie d'autres actifs et découvre la diversification.");
        }
        if (compared >= 3 && beat == 0) {
            add(recos, "indicators", Priority.MEDIUM, "L'agent IA t'a battu sur tes " + compared
                    + " dernières parties : regarde quels indicateurs il utilise.");
        }

        String summary;
        if (recos.isEmpty()) {
            summary = "Tes statistiques sont solides (réussite " + pct(p.winRate()) + ", discipline " + pct(p.discipline())
                    + ") : continue comme ça.";
        } else {
            Recommendation top = sorted(recos).get(0);
            summary = "Priorité : " + STATION_NAMES.getOrDefault(top.stationId(), top.stationId()) + " — " + top.reasons().get(0);
        }
        return new Advice(sorted(recos), p.games(), beat, compared, beat >= SECRET_WINS, summary);
    }

    private static void add(Map<String, Recommendation> recos, String id, Priority pr, String reason) {
        Recommendation old = recos.get(id);
        if (old == null) {
            recos.put(id, new Recommendation(id, pr, new ArrayList<>(List.of(reason))));
            return;
        }
        List<String> reasons = new ArrayList<>(old.reasons());
        reasons.add(reason);
        Priority best = pr.ordinal() < old.priority().ordinal() ? pr : old.priority();
        recos.put(id, new Recommendation(id, best, reasons));
    }

    private static List<Recommendation> sorted(Map<String, Recommendation> recos) {
        return recos.values().stream().sorted(Comparator.comparing(Recommendation::priority)).toList();
    }

    static String pct(double v) {
        return Math.round(v * 100) + " %";
    }
}
