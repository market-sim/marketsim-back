package tn.esprit.marketsim.user;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    private UserLevel level;

    /** Solde du portefeuille virtuel (module 2). */
    private BigDecimal virtualBalance;

    private boolean enabled;

    /** Compteur d'echecs de connexion consecutifs (reinitialise apres un succes). */
    @Builder.Default
    private int failedLoginAttempts = 0;

    /** Si non null et dans le futur, la connexion est refusee jusqu'a cette date. */
    private LocalDateTime lockedUntil;

    private LocalDateTime createdAt;

    /** E-mail confirme par le lien recu a l'inscription ; null = compte cree avant cette verification (considere confirme). */
    private Boolean emailVerified;

    /** Secret de la double authentification (TOTP, base32), jamais renvoye au client. */
    @JsonIgnore
    @Column(length = 64)
    private String totpSecret;

    /** Double authentification activee (null = non). */
    private Boolean totpEnabled;

    /** Compte supprime par son proprietaire (donnees personnelles anonymisees). */
    private Boolean deleted;

    /** cle aleatoire de la photo de profil (change a chaque nouvelle photo), null si aucune photo */
    @JsonIgnore
    @Column(length = 36)
    private String avatarKey;

    /** Adresse publique de la photo de profil (null si aucune). */
    public String getAvatarUrl() {
        return avatarKey == null ? null : "/api/public/avatars/" + avatarKey;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        if (role == null) role = Role.USER;
        if (level == null) level = UserLevel.DEBUTANT;
        if (virtualBalance == null) virtualBalance = new BigDecimal("100000");
    }

    @JsonIgnore
    public boolean isTwoFactorOn() {
        return Boolean.TRUE.equals(totpEnabled) && totpSecret != null;
    }

    /** Pour le client : vrai si l'e-mail est confirme (les anciens comptes le sont d'office). */
    public boolean isEmailConfirmed() {
        return emailVerified == null || emailVerified;
    }

    @JsonIgnore
    public boolean isTemporarilyLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }
}
