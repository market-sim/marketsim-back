package tn.esprit.marketsim.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfigRepository;
import tn.esprit.marketsim.agent.ticker.MarketTickerService;
import tn.esprit.marketsim.education.CourseRepository;
import tn.esprit.marketsim.market.AssetRepository;
import tn.esprit.marketsim.notification.NotificationRepository;
import tn.esprit.marketsim.progression.LeaderboardService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;

/**
 * Recherche globale de la barre du haut : actifs (marche + bandeau en direct), agents de l'utilisateur, cours,
 * traders du classement, notifications, et comptes utilisateurs pour l'administrateur seulement.
 * Insensible aux accents et a la casse ; les resultats qui commencent par la recherche passent devant.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    static final int PER_TYPE = 5;
    static final int MAX_QUERY = 60;

    private final AssetRepository assets;
    private final MarketTickerService ticker;
    private final AgentConfigRepository agents;
    private final CourseRepository courses;
    private final LeaderboardService leaderboard;
    private final NotificationRepository notifications;
    private final UserRepository users;

    /**
     * @param type     ASSET, AGENT, COURSE, TRADER, NOTIFICATION ou USER
     * @param badge    petite etiquette (type d'actif, variation, niveau, role...)
     */
    public record Hit(String type, String title, String subtitle, String link, String badge, int score) {
    }

    public record Results(String query, List<Hit> hits) {
    }

    @Transactional(readOnly = true)
    public Results search(User viewer, String raw) {
        String norm = normalize(raw == null ? "" : raw.strip());
        final String q = norm.length() > MAX_QUERY ? norm.substring(0, MAX_QUERY) : norm;
        if (q.length() < 2) return new Results(raw, List.of());
        List<Hit> hits = new ArrayList<>();

        // actifs : ceux du module marche + ceux du bandeau en direct (sans doublon de symbole)
        Map<String, Hit> bySymbol = new LinkedHashMap<>();
        for (var a : assets.findAll()) {
            int s = score(q, a.getSymbol(), a.getName());
            if (s > 0) bySymbol.putIfAbsent(a.getSymbol().toUpperCase(Locale.ROOT), new Hit("ASSET", a.getSymbol(), a.getName(),
                    "/market?symbol=" + a.getSymbol(), a.getType() == null ? null : a.getType().name(), s));
        }
        for (var t : ticker.cachedQuotes()) {
            int s = score(q, t.symbol(), t.name());
            if (s > 0) bySymbol.putIfAbsent(t.symbol().toUpperCase(Locale.ROOT), new Hit("ASSET", t.symbol(), t.name(),
                    "/market?symbol=" + t.symbol(), String.format(Locale.FRANCE, "%+.2f %%", t.changePct()), s));
        }
        hits.addAll(top(bySymbol.values().stream().toList()));

        // agents : seulement ceux de l'utilisateur
        if (viewer != null) {
            hits.addAll(top(agents.findByOwnerId(viewer.getId()).stream()
                    .map(a -> new Hit("AGENT", a.getName(), "Stratégie " + a.getStrategy(), "/agent?agent=" + a.getId(),
                            a.isActive() ? "actif" : "en pause", score(q, a.getName(), String.valueOf(a.getStrategy()))))
                    .toList()));
        }

        hits.addAll(top(courses.findAll().stream()
                .map(c -> new Hit("COURSE", c.getTitle(), c.getDescription(), "/education?tab=courses",
                        c.getLevel() == null ? null : c.getLevel().name(), score(q, c.getTitle(), c.getDescription())))
                .toList()));

        // traders du classement (nom court, jamais l'e-mail)
        hits.addAll(top(leaderboard.leaderboard(viewer, 200).top().stream()
                .map(e -> new Hit("TRADER", e.displayName(), "Rang " + e.rank() + " · score " + e.skillScore() + "/100",
                        "/leaderboard", e.level().name(), score(q, e.displayName())))
                .toList()));

        if (viewer != null) {
            hits.addAll(top(notifications.findTop30ByUserIdOrderByCreatedAtDesc(viewer.getId()).stream()
                    .map(n -> new Hit("NOTIFICATION", n.getTitle(), n.getMessage(), n.getLink() == null ? "/notifications" : n.getLink(),
                            n.getType().name(), score(q, n.getTitle(), n.getMessage())))
                    .toList()));
        }

        // comptes : reserve a l'administrateur (nom et e-mail)
        if (viewer != null && viewer.getRole() == Role.ADMIN) {
            hits.addAll(top(users.findAll().stream()
                    .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                    .map(u -> new Hit("USER", u.getFirstName() + " " + u.getLastName(), u.getEmail(),
                            "/admin?q=" + u.getEmail(), u.getRole().name() + (u.isEnabled() ? "" : " · désactivé"),
                            score(q, u.getFirstName() + " " + u.getLastName(), u.getEmail())))
                    .toList()));
        }
        return new Results(raw, hits);
    }

    private static List<Hit> top(List<Hit> list) {
        return list.stream().filter(h -> h.score() > 0)
                .sorted(Comparator.comparingInt(Hit::score).reversed()
                        .thenComparing(Hit::title, Comparator.nullsLast(Comparator.<String>naturalOrder())))
                .limit(PER_TYPE).toList();
    }

    /** 0 = pas de correspondance ; plus le score est haut, plus le resultat est pertinent. */
    static int score(String q, String... fields) {
        int best = 0;
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null) continue;
            String f = normalize(fields[i]);
            int weight = i == 0 ? 2 : 1; // le titre compte plus que la description
            int s = 0;
            if (f.equals(q)) s = 100;
            else if (f.startsWith(q)) s = 70;
            else if (f.contains(" " + q) || f.contains("/" + q) || f.contains("-" + q)) s = 50;
            else if (f.contains(q)) s = 30;
            best = Math.max(best, s * weight);
        }
        return best;
    }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
