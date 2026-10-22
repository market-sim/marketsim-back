package tn.esprit.marketsim.user.activity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Journal d'activite Module 1. actorId / targetUserId sont des Long sans FK
 * pour conserver l'historique si le compte est supprime.
 */
@Entity
@Table(name = "user_activity_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant occurredAt;

    /** Nullable : ex. tentative de login sur un email inconnu. */
    private Long actorId;

    private Long targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ActivityAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityOutcome outcome;

    /** Code court sans secret (pas de mot de passe, token, body brut). */
    @Column(nullable = false, length = 64)
    private String reasonCode;

    /** Null pour les entrees historiques d'avant la tracabilite enrichie. */
    @Column(length = 36)
    private String correlationId;

    /** JSON des changements allowlistes ; null = details indisponibles (historique). */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String changesJson;

    @Builder.Default
    @Column(nullable = false)
    private boolean passwordChanged = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean detailsAvailable = false;
}
