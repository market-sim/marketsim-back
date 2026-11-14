package tn.esprit.marketsim.user.account;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * E-mail envoye par la plateforme. Sans serveur SMTP (cas du projet en local), les messages restent dans cette
 * boite d'envoi, consultable par l'administrateur : c'est la que l'on recupere les liens de reinitialisation.
 */
@Entity
@Table(name = "mail_outbox")
@Getter
@Setter
@NoArgsConstructor
public class MailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String body;

    /** Lien principal du message (bouton), pour l'afficher directement dans la boite d'envoi. */
    @Column(length = 512)
    private String actionUrl;

    @Column(length = 20)
    private String kind;

    private Instant createdAt;

    /** LOCAL (pas de serveur SMTP configure), PENDING, SENT ou FAILED */
    @Column(length = 10)
    private String status;

    @Column(length = 300)
    private String error;

    private Instant sentAt;
}
