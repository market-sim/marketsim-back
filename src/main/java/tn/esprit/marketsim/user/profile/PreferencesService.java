package tn.esprit.marketsim.user.profile;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.passkey.WebAuthn;

/** Lecture et mise a jour des preferences ; chaque appel a sa propre transaction (creation sans conflit). */
@Service
@RequiredArgsConstructor
public class PreferencesService {

    static final Set<String> LANGUAGES = Set.of("fr", "en", "ar");
    static final Set<String> THEMES = Set.of("light", "dark", "system");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final UserPreferencesRepository repository;

    public record PreferencesRequest(String language, String theme, Boolean inAppProgress, Boolean inAppSecurity,
                                     Boolean emailSecurity, Boolean emailProgress, Boolean publicProfile, String bio) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserPreferences get(Long userId) {
        ensure(userId);
        return repository.findById(userId).orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserPreferences update(Long userId, PreferencesRequest req) {
        ensure(userId);
        UserPreferences p = repository.findById(userId).orElseThrow();
        if (req.language() != null) {
            if (!LANGUAGES.contains(req.language())) throw new IllegalArgumentException("Langue inconnue : fr, en ou ar");
            p.setLanguage(req.language());
        }
        if (req.theme() != null) {
            if (!THEMES.contains(req.theme())) throw new IllegalArgumentException("Thème inconnu : light, dark ou system");
            p.setTheme(req.theme());
        }
        if (req.inAppProgress() != null) p.setInAppProgress(req.inAppProgress());
        if (req.inAppSecurity() != null) p.setInAppSecurity(req.inAppSecurity());
        if (req.emailSecurity() != null) p.setEmailSecurity(req.emailSecurity());
        if (req.emailProgress() != null) p.setEmailProgress(req.emailProgress());
        if (req.publicProfile() != null) p.setPublicProfile(req.publicProfile());
        if (req.bio() != null) {
            String bio = req.bio().strip();
            if (bio.length() > 160) throw new IllegalArgumentException("La bio fait 160 caractères maximum");
            p.setBio(bio.isEmpty() ? null : bio);
        }
        p.setUpdatedAt(Instant.now());
        return repository.save(p);
    }

    /** Change le lien public (l'ancien lien ne marche plus). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserPreferences regeneratePublicId(Long userId) {
        ensure(userId);
        UserPreferences p = repository.findById(userId).orElseThrow();
        p.setPublicId(randomId());
        p.setUpdatedAt(Instant.now());
        return repository.save(p);
    }

    /**
     * Toujours en premiere instruction de la transaction : INSERT IGNORE est une ecriture (elle voit les lignes
     * deja validees par les autres), puis la lecture qui suit voit la ligne, qu'elle vienne de nous ou d'eux.
     */
    private void ensure(Long userId) {
        byte[] handle = new byte[32];
        RANDOM.nextBytes(handle);
        repository.insertIfAbsent(userId, randomId(), WebAuthn.b64url(handle), Instant.now());
    }

    static String randomId() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
