package tn.esprit.marketsim.user.account;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Jeton a usage unique envoye par e-mail (mot de passe oublie, confirmation d'e-mail). Seul son hash est stocke. */
@Entity
@Table(name = "account_tokens")
@Getter
@Setter
@NoArgsConstructor
public class AccountToken {

    public enum Purpose { RESET_PASSWORD, VERIFY_EMAIL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Purpose purpose;

    /** SHA-256 du jeton : une fuite de la base ne donne pas de lien utilisable. */
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    private Instant createdAt;
    private Instant expiresAt;
    private Instant usedAt;
}
