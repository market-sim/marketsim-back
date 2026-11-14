package tn.esprit.marketsim.user.passkey;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Defis WebAuthn en attente : aleatoires (32 octets), valables 5 minutes et utilisables UNE seule fois
 * (protection contre le rejeu). Gardes en memoire : suffisant pour un serveur unique.
 */
@Component
public class ChallengeStore {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Purpose { REGISTER, LOGIN }

    record Pending(Purpose purpose, Long userId, Instant expiresAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public String issue(Purpose purpose, Long userId) {
        cleanup();
        if (pending.size() > 10_000) throw new IllegalStateException("Trop de demandes en cours, réessaie dans un instant");
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String challenge = WebAuthn.b64url(b);
        pending.put(challenge, new Pending(purpose, userId, Instant.now().plus(TTL)));
        return challenge;
    }

    /** Consomme le defi : vrai s'il existait, n'etait pas expire et correspond au but et a l'utilisateur. */
    public boolean consume(String challenge, Purpose purpose, Long userId) {
        if (challenge == null) return false;
        Pending p = pending.remove(challenge);
        return Optional.ofNullable(p)
                .filter(x -> x.expiresAt().isAfter(Instant.now()))
                .filter(x -> x.purpose() == purpose)
                .filter(x -> userId == null ? x.userId() == null : userId.equals(x.userId()))
                .isPresent();
    }

    private void cleanup() {
        Instant now = Instant.now();
        pending.values().removeIf(p -> p.expiresAt().isBefore(now));
    }
}
