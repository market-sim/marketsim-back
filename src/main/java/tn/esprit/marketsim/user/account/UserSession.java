package tn.esprit.marketsim.user.account;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Session de connexion cote serveur. Chaque JWT porte l'identifiant de sa session (claim « sid ») :
 * se deconnecter ou revoquer la session rend le jeton inutilisable immediatement.
 */
@Entity
@Table(name = "user_sessions", indexes = @Index(name = "idx_user_sessions_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private Long userId;

    private Instant createdAt;
    private Instant lastSeenAt;
    private Instant expiresAt;

    /** Navigateur et systeme (resume lisible du User-Agent). */
    @Column(length = 120)
    private String device;

    @Column(length = 64)
    private String ipAddress;

    private Instant revokedAt;

    @Column(length = 40)
    private String revokedReason;

    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
