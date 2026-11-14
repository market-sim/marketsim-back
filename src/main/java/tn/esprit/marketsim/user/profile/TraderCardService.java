package tn.esprit.marketsim.user.profile;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.progression.LeaderboardService;
import tn.esprit.marketsim.progression.LeaderboardService.Badge;
import tn.esprit.marketsim.progression.ProgressionRules;
import tn.esprit.marketsim.progression.TraderProfile;
import tn.esprit.marketsim.progression.TraderProfileRepository;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;

/**
 * Carte de trader : nom court (jamais l'e-mail), photo, niveau, score, rang et badges.
 * Visible par un lien public seulement si l'utilisateur l'a activee dans ses preferences.
 */
@Service
@RequiredArgsConstructor
public class TraderCardService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE);

    private final PreferencesService preferences;
    private final UserPreferencesRepository preferencesRepository;
    private final TraderProfileRepository profileRepository;
    private final LeaderboardService leaderboard;
    private final UserRepository userRepository;

    public record TraderCard(String publicId, boolean isPublic, String displayName, String avatarUrl, UserLevel level,
                             UserLevel bestLevel, int skillScore, int games, int trades, double winRate, double discipline,
                             Integer rank, int traders, List<Badge> badges, String memberSince, String bio) {
    }

    @Transactional(readOnly = true)
    public TraderCard own(User user) {
        return build(userRepository.findById(user.getId()).orElse(user), preferences.get(user.getId()));
    }

    @Transactional(readOnly = true)
    public Optional<TraderCard> publicCard(String publicId) {
        Optional<UserPreferences> prefs = preferencesRepository.findByPublicId(publicId).filter(UserPreferences::isPublicProfile);
        if (prefs.isEmpty()) return Optional.empty();
        return userRepository.findById(prefs.get().getUserId())
                .filter(u -> u.isEnabled() && !Boolean.TRUE.equals(u.getDeleted()) && u.getRole() != Role.ADMIN)
                .map(u -> build(u, prefs.get()));
    }

    private TraderCard build(User u, UserPreferences prefs) {
        Optional<TraderProfile> profile = profileRepository.findByUserId(u.getId());
        Optional<LeaderboardService.Entry> entry = u.getRole() == Role.ADMIN ? Optional.empty() : leaderboard.entryOf(u);
        int traders = leaderboard.rankedCount(u);
        ProgressionRules.Stats s = profile.map(TraderProfile::stats).orElse(ProgressionRules.Stats.fresh());
        String since = u.getCreatedAt() == null ? null : u.getCreatedAt().format(MONTH);
        return new TraderCard(prefs.getPublicId(), prefs.isPublicProfile(), displayName(u), u.getAvatarUrl(),
                profile.map(TraderProfile::getLevel).orElse(UserLevel.DEBUTANT),
                profile.map(TraderProfile::getBestLevel).orElse(UserLevel.DEBUTANT),
                ProgressionRules.skillScore(s), s.games(), s.trades(), s.winRate(), s.discipline(),
                entry.map(LeaderboardService.Entry::rank).orElse(null), traders,
                entry.map(LeaderboardService.Entry::badges).orElse(List.of()), since, prefs.getBio());
    }

    static String displayName(User u) {
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last = u.getLastName() == null || u.getLastName().isBlank() ? "" : " " + u.getLastName().trim().charAt(0) + ".";
        return (first.isEmpty() ? "Trader" : first) + last;
    }
}
