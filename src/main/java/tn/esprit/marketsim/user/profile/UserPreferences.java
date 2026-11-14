package tn.esprit.marketsim.user.profile;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Preferences du compte, enregistrees sur le serveur (elles suivent l'utilisateur sur tous ses appareils) :
 * langue, theme, notifications (dans l'application / par e-mail) et carte de trader publique.
 */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
public class UserPreferences {

    @Id
    private Long userId;

    /** fr, en ou ar */
    @Column(nullable = false, length = 2)
    private String language = "fr";

    /** light, dark ou system */
    @Column(nullable = false, length = 10)
    private String theme = "system";

    /** notifications de progression (niveau, portefeuille, pilote automatique) dans la cloche */
    @Column(nullable = false)
    private boolean inAppProgress = true;

    /** alertes de securite dans la cloche (nouvel appareil, mot de passe, double authentification, passkeys) */
    @Column(nullable = false)
    private boolean inAppSecurity = true;

    /** alertes de securite par e-mail */
    @Column(nullable = false)
    private boolean emailSecurity = true;

    /** progression par e-mail (montee de niveau...) */
    @Column(nullable = false)
    private boolean emailProgress = false;

    /** la carte de trader est visible par un lien public */
    @Column(nullable = false)
    private boolean publicProfile = false;

    /** identifiant aleatoire du lien public (jamais l'id ni l'e-mail) */
    @Column(nullable = false, unique = true, length = 16)
    private String publicId;

    @Column(length = 160)
    private String bio;

    /** identifiant opaque de l'utilisateur pour les passkeys (WebAuthn user.id), jamais l'e-mail */
    @JsonIgnore
    @Column(nullable = false, length = 64)
    private String webauthnHandle;

    private Instant updatedAt;
}
