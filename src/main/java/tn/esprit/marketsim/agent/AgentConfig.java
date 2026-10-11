package tn.esprit.marketsim.agent;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    /** Ex : "Reinforcement Learning (PPO)", "Regles apprises". */
    private String strategy;

    @Min(0)
    @Max(100)
    private Integer riskLevel = 50;

    /** Actifs autorises, separes par des virgules : "AAPL,TSLA,BTC/USD". */
    private String allowedAssets;

    private BigDecimal maxPositionSize;

    private boolean active;

    private LocalDateTime createdAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
