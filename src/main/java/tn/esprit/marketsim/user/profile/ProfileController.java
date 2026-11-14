package tn.esprit.marketsim.user.profile;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.profile.PreferencesService.PreferencesRequest;
import tn.esprit.marketsim.user.profile.TraderCardService.TraderCard;

/** Profil enrichi : preferences (langue, theme, notifications, profil public), photo et carte de trader. */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class ProfileController {

    private final PreferencesService preferences;
    private final AvatarService avatars;
    private final TraderCardService cards;
    private final ActivityLogService activityLogService;

    public record AvatarRequest(String image) {
    }

    @GetMapping("/preferences")
    public UserPreferences getPreferences(@AuthenticationPrincipal User user) {
        return preferences.get(user.getId());
    }

    @PutMapping("/preferences")
    public UserPreferences updatePreferences(@RequestBody PreferencesRequest req, @AuthenticationPrincipal User user) {
        UserPreferences p = preferences.update(user.getId(), req);
        activityLogService.recordImmediate(user.getId(), user.getId(), ActivityAction.PREFERENCES_UPDATE,
                tn.esprit.marketsim.user.activity.ActivityOutcome.SUCCESS, "OK");
        return p;
    }

    /** Nouveau lien public : l'ancien ne fonctionne plus. */
    @PostMapping("/preferences/new-public-link")
    public UserPreferences newPublicLink(@AuthenticationPrincipal User user) {
        return preferences.regeneratePublicId(user.getId());
    }

    @PutMapping("/avatar")
    public Map<String, Object> uploadAvatar(@RequestBody AvatarRequest req, @AuthenticationPrincipal User user) {
        User u = avatars.upload(user, req.image());
        return Map.of("avatarUrl", u.getAvatarUrl());
    }

    @DeleteMapping("/avatar")
    public void deleteAvatar(@AuthenticationPrincipal User user) {
        avatars.remove(user);
    }

    @GetMapping("/card")
    public TraderCard card(@AuthenticationPrincipal User user) {
        return cards.own(user);
    }
}
