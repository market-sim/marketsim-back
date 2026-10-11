package tn.esprit.marketsim.agent.decision;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentConfig;

/** Module 6 : decision prise par un agent, avec son explication XAI et l'evaluation de l'utilisateur. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class AgentDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AgentConfig agent;

    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private AgentAction action;

    private double quantity;

    private double price;

    private double confidence;

    private double score;

    private Double stopLoss;

    private Double takeProfit;

    /** Explication en langage naturel generee a partir des contributions. */
    @Column(length = 1000)
    private String explanation;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_decision_contribution")
    @OrderColumn(name = "rank_index")
    private List<FeatureContribution> contributions = new ArrayList<>();

    /** Valeurs Q (HOLD,BUY,SELL) pour un agent Q-learning, sinon null. */
    private String qValues;

    /** Ordre cree dans le module 2 si l'agent etait actif. */
    private Long orderId;

    /** Evaluation de la decision par l'utilisateur (1 a 5 etoiles). */
    private Integer userRating;

    @Column(length = 500)
    private String userComment;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
