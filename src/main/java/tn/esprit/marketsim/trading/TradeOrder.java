package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.User;

/**
 * Module 2 : ticket de trading (modele « ticket / lot »). Un ordre execute = une position ouverte independante.
 * <ul>
 *   <li>BUY = position LONG, SELL = position SHORT (marge de 100 %).</li>
 *   <li>{@code price} = prix de reference : prix d'execution une fois rempli, niveau limite / stop tant qu'il est
 *       en attente (colonne historique gardee pour la compatibilite du front et du module 1).</li>
 * </ul>
 * Toutes les nouvelles colonnes sont nullables (base existante + {@code ddl-auto=update}) ; voir
 * {@link TradingSchemaMigration} pour la migration des anciennes lignes.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class TradeOrder {

    public enum Side { BUY, SELL }

    public enum OrderType { MARKET, LIMIT, STOP }

    /** PENDING : LIMIT/STOP en attente ; OPEN : position ouverte ; CLOSED, CANCELLED, REJECTED : terminaux. */
    public enum Status { PENDING, OPEN, CLOSED, CANCELLED, REJECTED }

    public enum CloseReason { MANUAL, STOP_LOSS, TAKE_PROFIT, AGENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Side side;

    @Enumerated(EnumType.STRING)
    private OrderType orderType = OrderType.MARKET;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    /** Prix de reference (execution, ou niveau limite / stop en attente). Jamais fourni par le client. */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal price;

    @Column(precision = 19, scale = 6)
    private BigDecimal stopLoss;

    @Column(precision = 19, scale = 6)
    private BigDecimal takeProfit;

    /** VARCHAR plutot qu'un ENUM MySQL : on peut ajouter des statuts sans migration manuelle. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private Status status = Status.OPEN;

    private LocalDateTime createdAt;

    // ----- Champs du moteur de trading (module 2 complet) -----

    /** Niveau de declenchement d'un ordre LIMIT ou STOP. */
    @Column(precision = 19, scale = 6)
    private BigDecimal limitPrice;

    @Column(precision = 19, scale = 6)
    private BigDecimal executedPrice;

    private LocalDateTime executedAt;

    private LocalDateTime closedAt;

    @Column(precision = 19, scale = 6)
    private BigDecimal closePrice;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private CloseReason closeReason;

    /** P&L realise brut (hors frais) : (sortie - entree) x qte pour un LONG, l'inverse pour un SHORT. */
    @Column(precision = 19, scale = 2)
    private BigDecimal realizedPnl;

    /** Frais cumules (ouverture + cloture). */
    @Column(precision = 19, scale = 2)
    private BigDecimal fees;

    @Column(length = 500)
    private String rejectReason;

    /**
     * Ticket ouvert avant le moteur de trading : son ouverture n'a jamais debite le solde. A la cloture, seul le
     * P&L (moins les frais) est credite, pas le principal.
     */
    private Boolean legacy;

    /** Verrou optimiste : empeche deux executions / clotures concurrentes du meme ticket. */
    @Version
    private Long version;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (legacy == null) legacy = false;
    }

    @JsonIgnore
    public boolean isLegacy() {
        return Boolean.TRUE.equals(legacy);
    }

    /** Prix d'entree effectif d'un ticket rempli (executedPrice, ou price pour les tres anciennes lignes). */
    @JsonIgnore
    public BigDecimal entryPrice() {
        return executedPrice != null ? executedPrice : price;
    }
}
