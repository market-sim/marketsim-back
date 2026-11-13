package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

/** Point de la courbe d'equity, enregistre a chaque mouvement de solde (execution ou cloture d'un ticket). */
@Entity
@Table(name = "trading_equity_snapshot", indexes = @Index(name = "idx_equity_owner_at", columnList = "ownerId, takenAt"))
@Getter
@Setter
@NoArgsConstructor
public class EquitySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private LocalDateTime takenAt;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal equity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal cash;

    /** Evenement a l'origine du point (ORDER_FILLED, ORDER_CLOSED...). */
    @Column(length = 30)
    private String event;
}
