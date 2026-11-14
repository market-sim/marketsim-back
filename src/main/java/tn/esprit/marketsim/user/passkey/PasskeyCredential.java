package tn.esprit.marketsim.user.passkey;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Passkey d'un utilisateur : seule la cle PUBLIQUE est gardee. La cle privee ne quitte jamais l'appareil
 * (puce securisee du telephone, Windows Hello, trousseau iCloud / Google) : une fuite de la base ne permet pas
 * de se connecter.
 */
@Entity
@Table(name = "user_passkeys", indexes = @Index(name = "idx_passkeys_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class PasskeyCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @Column(nullable = false)
    private Long userId;

    /** identifiant de la passkey (base64url), choisi par l'authentificateur */
    @JsonIgnore
    @Column(nullable = false, unique = true, length = 700)
    private String credentialId;

    @JsonIgnore
    @Column(nullable = false, columnDefinition = "BLOB")
    private byte[] publicKey;

    /** algorithme COSE : -7 (ES256) ou -257 (RS256) */
    @JsonIgnore
    private long alg;

    @JsonIgnore
    private long signCount;

    /** nom donne par l'utilisateur (« iPhone de Yasmine », « PC Windows Hello »...) */
    @Column(nullable = false, length = 60)
    private String name;

    /** synchronisee entre appareils (iCloud, Google) */
    private boolean backedUp;

    @Column(length = 120)
    private String transports;

    private Instant createdAt;

    private Instant lastUsedAt;
}
