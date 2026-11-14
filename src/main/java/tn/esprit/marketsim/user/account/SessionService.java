package tn.esprit.marketsim.user.account;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyModel;

/** Sessions de connexion cote serveur : ouverture a la connexion, controle a chaque requete, revocation. */
@Service
public class SessionService {

    /** On ne reecrit « vue pour la derniere fois » qu'au plus une fois par minute. */
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final UserSessionRepository repository;
    private final long ttlMs;

    public SessionService(UserSessionRepository repository, @Value("${app.jwt.expiration-ms}") long ttlMs) {
        this.repository = repository;
        this.ttlMs = ttlMs;
    }

    @Transactional
    public UserSession open(Long userId, HttpServletRequest request) {
        Instant now = Instant.now();
        UserSession s = UserSession.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .createdAt(now)
                .lastSeenAt(now)
                .expiresAt(now.plusMillis(ttlMs))
                .device(request == null ? null : describe(request.getHeader("User-Agent")))
                .ipAddress(request == null ? null : clientIp(request))
                .build();
        return repository.save(s);
    }

    /** Vrai si la session existe, appartient a l'utilisateur et n'est ni revoquee ni expiree. */
    @Transactional
    public boolean touchIfActive(String sessionId, Long userId) {
        Instant now = Instant.now();
        return repository.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId) && s.isActive(now))
                .map(s -> {
                    if (s.getLastSeenAt() == null || s.getLastSeenAt().plus(TOUCH_EVERY).isBefore(now)) {
                        s.setLastSeenAt(now);
                        repository.save(s);
                    }
                    return true;
                })
                .orElse(false);
    }

    /** Vrai si l'utilisateur s'est deja connecte avec cet appareil (meme navigateur et systeme). */
    @Transactional(readOnly = true)
    public boolean knownDevice(Long userId, String device) {
        return repository.findByUserId(userId).stream().anyMatch(s -> java.util.Objects.equals(s.getDevice(), device));
    }

    @Transactional(readOnly = true)
    public boolean hasHistory(Long userId) {
        return !repository.findByUserId(userId).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<UserSession> active(Long userId) {
        Instant now = Instant.now();
        return repository.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId).stream()
                .filter(s -> s.isActive(now))
                .toList();
    }

    /** Revoque une session de l'utilisateur ; faux si elle n'existe pas ou ne lui appartient pas. */
    @Transactional
    public boolean revoke(Long userId, String sessionId, String reason) {
        return repository.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId) && s.getRevokedAt() == null)
                .map(s -> {
                    s.setRevokedAt(Instant.now());
                    s.setRevokedReason(reason);
                    repository.save(s);
                    return true;
                })
                .orElse(false);
    }

    /** Revoque toutes les sessions sauf celle indiquee (null = toutes) ; renvoie le nombre de sessions coupees. */
    @Transactional
    public int revokeAll(Long userId, String exceptSessionId, String reason) {
        Instant now = Instant.now();
        int n = 0;
        for (UserSession s : repository.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId)) {
            if (s.getId().equals(exceptSessionId)) continue;
            s.setRevokedAt(now);
            s.setRevokedReason(reason);
            repository.save(s);
            n++;
        }
        return n;
    }

    /** Session de la requete en cours (posee par le filtre JWT), ou null pour un ancien jeton sans session. */
    public static String currentSessionId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof String sid ? sid : null;
    }

    static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Appareil inconnu";
        String browser = switch (LoginAnomalyModel.normalizeBrowserFamily(userAgent)) {
            case "chrome" -> "Chrome";
            case "firefox" -> "Firefox";
            case "safari" -> "Safari";
            case "edge" -> "Edge";
            case "opera" -> "Opera";
            default -> "Navigateur";
        };
        String ua = userAgent.toLowerCase();
        String os = ua.contains("windows") ? "Windows"
                : ua.contains("android") ? "Android"
                : ua.contains("iphone") || ua.contains("ipad") ? "iOS"
                : ua.contains("mac os") ? "macOS"
                : ua.contains("linux") ? "Linux" : "système inconnu";
        return browser + " · " + os;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return ip == null ? null : ip.length() > 64 ? ip.substring(0, 64) : ip;
    }
}
