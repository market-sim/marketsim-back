package tn.esprit.marketsim.agent.autopilot;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.agent.AgentConfig;

/**
 * Session de pilote automatique : l'agent trade seul, a intervalle regulier, sur un marche simule
 * en continu, avec un portefeuille papier et des garde-fous (perte max, ordres par jour, coupe-circuit).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class AutopilotSession {

    public enum Status { RUNNING, PAUSED, STOPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AgentConfig agent;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status status;

    @Column(length = 500)
    private String stopReason;

    // ----- Reglages -----
    /** Duree reelle d'un "jour de bourse" simule, en secondes. */
    private int intervalSeconds;
    private int maxOrdersPerDay;
    /** Perte maximale tolereee depuis le debut, en % du capital ; atteinte = arret. */
    private double maxLossPct;
    /** Arret automatique si la surveillance comportementale confirme une anomalie. */
    private boolean killSwitch;

    // ----- Portefeuille papier -----
    private double capital;
    private double cash;
    private double peakEquity;
    private int step;
    private int trades;
    private int wins;

    /** Valeur du portefeuille (base 100) a chaque pas, CSV. */
    @Column(columnDefinition = "TEXT")
    private String equityCsv;

    /** Buy &amp; hold equipondere des memes actifs (base 100), CSV. */
    @Column(columnDefinition = "TEXT")
    private String benchmarkCsv;

    // ----- Garde-fous -----
    private LocalDate ordersDay;
    private int ordersToday;
    private int decisionsSinceCheck;
    private int consecutiveAlerts;

    private LocalDateTime startedAt;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;

    @PrePersist
    void onCreate() {
        startedAt = LocalDateTime.now();
    }
}
