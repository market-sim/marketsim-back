package tn.esprit.marketsim.agent.autopilot;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

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

/** Journal du pilote automatique : ordres executes et evenements de controle. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class AutopilotEvent {

    public enum Type {
        STARTED, BUY, SELL, STOP_LOSS, TAKE_PROFIT, PAUSED, RESUMED, STOPPED,
        LOSS_LIMIT, ORDER_LIMIT, ANOMALY_WARNING, KILL_SWITCH, ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AutopilotSession session;

    private LocalDateTime at;

    /** Jour simule. */
    private int step;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Type type;

    private String symbol;

    private Double price;

    private Double quantity;

    /** Valeur du portefeuille (base 100) apres l'evenement. */
    private Double equity;

    @Column(length = 600)
    private String message;

    private Long decisionId;

    @PrePersist
    void onCreate() {
        if (at == null) at = LocalDateTime.now();
    }
}
