package tn.esprit.marketsim.user.profile;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.passkey.PasskeyRepository;

/** Donnees du profil enrichi dans l'export RGPD, et leur suppression avec le compte. */
@Component
@RequiredArgsConstructor
public class AccountDataCleaner {

    private final UserPreferencesRepository preferences;
    private final UserAvatarRepository avatars;
    private final PasskeyRepository passkeys;

    @Transactional(readOnly = true)
    public Map<String, Object> export(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preferences", preferences.findById(userId).orElse(null));
        out.put("hasAvatar", avatars.existsById(userId));
        out.put("passkeys", passkeys.findByUserIdOrderByCreatedAtDesc(userId));
        return out;
    }

    /** Photo, passkeys et preferences (donc la carte publique) disparaissent avec le compte. */
    @Transactional
    public void delete(Long userId) {
        avatars.deleteById(userId);
        passkeys.deleteAll(passkeys.findByUserIdOrderByCreatedAtDesc(userId));
        preferences.deleteById(userId);
    }
}
