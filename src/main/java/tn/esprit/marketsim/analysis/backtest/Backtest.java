package tn.esprit.marketsim.analysis.backtest;

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
import tn.esprit.marketsim.analysis.Strategy;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : un backtest lance par un utilisateur. Les metriques principales sont en colonnes (pour la liste) ;
 * le resultat complet (toutes les metriques, courbes, trades) est garde en JSON.
 * Cette entite n'est jamais renvoyee telle quelle au front : voir BacktestDtos.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Backtest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Strategy strategy;

    // Copie de la strategie au moment du lancement (elle peut etre modifiee ensuite)
    @Column(nullable = false)
    private String strategyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(32)")
    private StrategyType strategyType;

    @Column(nullable = false, length = 32)
    private String symbol;

    // Periode reellement testee (premiere et derniere seance)
    @Column(nullable = false)
    private LocalDate fromDate;

    @Column(nullable = false)
    private LocalDate toDate;

    /** Date de coupure entrainement / test (null si non demandee). */
    private LocalDate splitDate;

    // Metriques principales (fractions : 0.12 = 12 %)
    private Double totalReturn;
    private Double annualizedReturn;
    private Double sharpe;
    private Double maxDrawdown;
    private Integer trades;
    private Double winRate;
    private Double buyHoldReturn;
    private Double excessReturn;

    /** Origine de l'historique utilise : YAHOO ou CSV. */
    @Column(length = 16)
    private String dataSource;

    /** Empreinte SHA-256 de l'historique utilise (reproductibilite). */
    @Column(length = 64)
    private String dataHash;

    /** JSON : parametres de la strategie et reglages du backtest (BacktestDtos.StoredSettings). */
    @Column(columnDefinition = "TEXT")
    private String settingsJson;

    /** JSON : resultat complet (BacktestDtos.BacktestResult). */
    @Column(columnDefinition = "LONGTEXT")
    private String resultJson;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
