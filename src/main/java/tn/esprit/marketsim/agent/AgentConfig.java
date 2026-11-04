package tn.esprit.marketsim.agent;

import java.math.BigDecimal;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.User;

/** Module 6 : configuration d'un agent de trading IA. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class AgentConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AgentStrategy strategy = AgentStrategy.MOMENTUM;

    /** Appetit pour le risque : 0 = tres prudent, 100 = tres agressif. */
    @Min(0)
    @Max(100)
    private Integer riskLevel = 50;

    /** Actifs autorises, separes par des virgules : "AAPL,TSLA,BTC/USD". */
    private String allowedAssets;

    /** Montant maximum engage par position (en $ virtuels). */
    private BigDecimal maxPositionSize = new BigDecimal("10000");

    /** Agent actif = ses decisions BUY/SELL sont executees comme ordres reels (module 2). */
    private boolean active;

    private LocalDateTime createdAt;

    // ----- Etat d'apprentissage (strategie Q_LEARNING) -----

    /** Q-table apprise, serialisee en CSV (voir QTable). Null tant que l'agent n'est pas entraine. */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String qTable;

    private Integer trainedEpisodes;

    private String trainedOn;

    private LocalDateTime trainedAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    /** Cle etrangere du proprietaire, lisible sans charger l'utilisateur (journal d'audit). */
    @JsonIgnore
    @Column(name = "owner_id", insertable = false, updatable = false)
    private Long ownerId;

    /** Proprietaire : la colonne lue en base, ou l'utilisateur fourni a la creation (agent pas encore recharge). */
    public Long getOwnerId() {
        return ownerId != null ? ownerId : owner != null ? owner.getId() : null;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** Expose dans le JSON : l'agent possede-t-il une politique apprise ? */
    public boolean isTrained() {
        return qTable != null;
    }

    /** Capital maximum par position (10 000 $ par defaut). */
    public double maxPosition() {
        return maxPositionSize == null ? 10000 : maxPositionSize.doubleValue();
    }

    /** Risque normalise dans [0, 1]. */
    public double risk() {
        return (riskLevel == null ? 50 : riskLevel) / 100.0;
    }
}
