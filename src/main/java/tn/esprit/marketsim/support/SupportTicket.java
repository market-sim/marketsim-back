package tn.esprit.marketsim.support;

import java.time.Instant;

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
 * Conversation de support entre un trader et l'administration (demande de fonds, probleme, question...).
 * Les etats sont stockes en texte (pas en ENUM MySQL) pour pouvoir en ajouter sans migration.
 */
@Entity
@Table(name = "support_tickets", indexes = {
        @Index(name = "idx_support_user", columnList = "userId"),
        @Index(name = "idx_support_status", columnList = "status")})
@Getter
@Setter
@NoArgsConstructor
public class SupportTicket {

    public static final String OPEN = "OPEN";         // en attente de l'admin
    public static final String ANSWERED = "ANSWERED"; // l'admin a repondu, en attente du trader
    public static final String CLOSED = "CLOSED";     // resolu

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String subject;

    /** FUNDS, TECHNICAL, ACCOUNT, OTHER */
    @Column(nullable = false, length = 20)
    private String category;

    @Column(nullable = false, length = 10)
    private String status = OPEN;

    /** montant demande (categorie FUNDS) */
    private Double requestedAmount;

    /** PENDING, APPROVED, REJECTED (demande de fonds seulement) */
    @Column(length = 10)
    private String requestStatus;

    private Instant createdAt;

    private Instant lastMessageAt;

    /** dernier message du trader / de l'administration */
    private Instant lastUserMessageAt;

    private Instant lastAdminMessageAt;

    /** derniere lecture par le trader / par l'administration (pour les messages non lus) */
    private Instant userReadAt;

    private Instant adminReadAt;
}
