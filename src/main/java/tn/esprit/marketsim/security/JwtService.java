package tn.esprit.marketsim.security;

import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import tn.esprit.marketsim.user.User;

@Service
public class JwtService {

    /** Identifiant de la session serveur (deconnexion et revocation immediates). */
    public static final String SESSION_CLAIM = "sid";
    /** Jeton a usage restreint (ex. etape 2 de la connexion) : refuse par le filtre d'authentification. */
    public static final String PURPOSE_CLAIM = "purpose";
    public static final String PURPOSE_TWO_FACTOR = "2fa";
    private static final long CHALLENGE_MS = 5 * 60 * 1000L;

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    public long expirationMs() {
        return expirationMs;
    }

    public String generateToken(User user) {
        return generateToken(user, null);
    }

    /** Jeton de connexion rattache a une session serveur (sid). */
    public String generateToken(User user, String sessionId) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs));
        if (sessionId != null) builder.claim(SESSION_CLAIM, sessionId);
        return builder.signWith(key).compact();
    }

    /** Jeton court (5 min) qui prouve que le mot de passe est bon et attend le code de double authentification. */
    public String generateTwoFactorChallenge(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(PURPOSE_CLAIM, PURPOSE_TWO_FACTOR)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + CHALLENGE_MS))
                .signWith(key)
                .compact();
    }

    /** Email du jeton d'etape 2, ou exception si le jeton est invalide, expire ou d'un autre type. */
    public String extractTwoFactorEmail(String challenge) {
        Claims c = parse(challenge);
        if (!PURPOSE_TWO_FACTOR.equals(c.get(PURPOSE_CLAIM, String.class))) {
            throw new IllegalArgumentException("Jeton de double authentification invalide");
        }
        return c.getSubject();
    }

    /** Verifie la signature et l'expiration ; leve une exception sinon. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** Retourne l'email contenu dans le token, ou leve une exception si le token est invalide/expire. */
    public String extractEmail(String token) {
        return parse(token).getSubject();
    }
}
