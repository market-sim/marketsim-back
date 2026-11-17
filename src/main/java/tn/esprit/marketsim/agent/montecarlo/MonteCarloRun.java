package tn.esprit.marketsim.agent.montecarlo;

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
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.engine.PriceModel;

/**
 * Resume d'une simulation Monte Carlo. Les graphiques ne sont pas stockes :
 * relancer avec le meme seed reproduit exactement le meme resultat.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class MonteCarloRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AgentConfig agent;

    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AgentStrategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PriceModel model;

    private int paths;

    private int horizonDays;

    private long seed;

    private boolean antithetic;

    private Double mu;

    private Double sigma;

    private double meanReturn;

    private double var95;

    private double cvar95;

    private double probProfit;

    private double probBeatBenchmark;

    private double benchmarkMeanReturn;

    private double meanSharpe;

    private double meanMaxDrawdown;

    /** Taux de trades gagnants de l'agent sur l'ensemble des trajectoires. */
    private double winRate;

    // ----- Scenario de krach (null = aucun) -----
    private Double crashPct;
    private Integer crashDay;
    private Double volMultiplier;
    private Integer volDays;

    private long elapsedMs;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
