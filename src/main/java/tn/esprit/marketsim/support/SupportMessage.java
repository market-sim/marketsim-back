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

/** Message d'une conversation de support ; system = evenement automatique (demande acceptee, conversation fermee...). */
@Entity
@Table(name = "support_messages", indexes = @Index(name = "idx_support_msg_ticket", columnList = "ticketId"))
@Getter
@Setter
@NoArgsConstructor
public class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ticketId;

    /** null pour un message automatique */
    private Long authorId;

    @Column(nullable = false)
    private boolean fromAdmin;

    @Column(nullable = false)
    private boolean system;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    private Instant createdAt;
}
