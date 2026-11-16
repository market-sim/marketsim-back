package tn.esprit.marketsim.progression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.progression.ProgressionRules.Stats;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;

/**
 * Classement public des traders : rang, niveau, score de competence et portefeuille, sans e-mail
 * (seuls le prenom et l'initiale du nom sont affiches). L'administrateur et les comptes desactives n'y figurent pas.
 */
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final TraderProfileRepository profileRepository;
    private final TraderGameRepository gameRepository;
    private final UserRepository userRepository;
    /** Solde unique : la copie du profil est rafraichie avant le classement. */
    private final WalletService wallet;

    /** Badge gagne : identifiant stable, libelle et explication. */
    public record Badge(String id, String label, String description) {
    }

    public record Entry(int rank, String displayName, boolean me, UserLevel level, int skillScore, double balance,
                        int games, int trades, double winRate, List<Badge> badges) {
    }

    public record Leaderboard(int traders, Entry me, List<Entry> top) {
    }

    @Transactional(readOnly = true)
    public Leaderboard leaderboard(User viewer, int limit) {
        Map<Long, User> users = new HashMap<>();
        userRepository.findAll().forEach(u -> {
            if (u.getRole() != Role.ADMIN && u.isEnabled() && !Boolean.TRUE.equals(u.getDeleted())) users.put(u.getId(), u);
        });
        // solde unique (users.virtual_balance) recopie dans chaque profil avant le tri
        List<TraderProfile> ranked = profileRepository.findAll().stream()
                .filter(p -> users.containsKey(p.getUserId()) && p.getGames() > 0)
                .map(p -> wallet.mirror(p, wallet.cashOf(users.get(p.getUserId()))))
                .sorted(ORDER)
                .toList();
        List<Entry> all = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            TraderProfile p = ranked.get(i);
            int beatAgent = (int) gameRepository.findTop100ByUserIdOrderByPlayedAtDesc(p.getUserId()).stream()
                    .filter(g -> !Boolean.FALSE.equals(g.getCounted()) && g.getAgentReturn() != null && g.getStartBalance() > 0
                            && g.getEndBalance() / g.getStartBalance() - 1 > g.getAgentReturn())
                    .count();
            Stats s = p.stats();
            all.add(new Entry(i + 1, displayName(users.get(p.getUserId())), viewer != null && p.getUserId().equals(viewer.getId()),
                    p.getLevel(), ProgressionRules.skillScore(s), ProgressionRules.round2(s.balance()), s.games(), s.trades(),
                    s.winRate(), badges(p, beatAgent)));
        }
        Entry me = all.stream().filter(Entry::me).findFirst().orElse(null);
        return new Leaderboard(all.size(), me, all.stream().limit(Math.max(1, limit)).toList());
    }

    /** Place d'un trader dans le classement (vide s'il n'a pas encore joue). */
    @Transactional(readOnly = true)
    public java.util.Optional<Entry> entryOf(User user) {
        return java.util.Optional.ofNullable(leaderboard(user, 1).me());
    }

    /** Nombre de traders classes. */
    @Transactional(readOnly = true)
    public int rankedCount(User user) {
        return leaderboard(user, 1).traders();
    }

    /** Meilleur score de competence d'abord, puis le niveau, puis le portefeuille. */
    static final Comparator<TraderProfile> ORDER = Comparator
            .comparingInt((TraderProfile p) -> ProgressionRules.skillScore(p.stats())).reversed()
            .thenComparing(Comparator.comparingInt((TraderProfile p) -> p.getLevel().ordinal()).reversed())
            .thenComparing(Comparator.comparingDouble(TraderProfile::getBalance).reversed());

    static List<Badge> badges(TraderProfile p, int gamesBeatingAgent) {
        Stats s = p.stats();
        List<Badge> out = new ArrayList<>();
        if (s.games() >= 1) out.add(new Badge("FIRST_GAME", "Premiers pas", "A joué sa première partie"));
        if (s.trades() >= 10 && s.discipline() >= 0.8) {
            out.add(new Badge("DISCIPLINED", "Discipliné", "Au moins 80 % des trades fermés avant de dépasser le stop-loss"));
        }
        if (gamesBeatingAgent >= 5) out.add(new Badge("BEAT_AGENT", "Plus fort que l'IA", "A battu l'agent sur 5 parties"));
        if (Math.max(p.getPeakBalance(), s.balance()) >= 2 * ProgressionRules.START_BALANCE) {
            out.add(new Badge("DOUBLED", "Capital doublé", "A doublé son capital de départ"));
        }
        if (p.getRestarts() > 0 && p.getLevel().ordinal() >= UserLevel.INTERMEDIAIRE.ordinal()) {
            out.add(new Badge("COMEBACK", "Phénix", "Ruiné une fois, il est remonté au moins Intermédiaire"));
        }
        if (p.getBestLevel() == UserLevel.EXPERT) out.add(new Badge("EXPERT", "Expert", "A atteint le niveau Expert"));
        return out;
    }

    static String displayName(User u) {
        if (u == null) return "Trader";
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last = u.getLastName() == null || u.getLastName().isBlank() ? "" : " " + u.getLastName().trim().charAt(0) + ".";
        return (first.isEmpty() ? "Trader" : first) + last;
    }
}
