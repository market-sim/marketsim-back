package tn.esprit.marketsim.agent.tournament;

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
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.user.User;

/** Historique d'un tournoi (relancer avec le meme seed reproduit exactement le resultat). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class TournamentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    /** Identifiants des agents, separes par des virgules. */
    private String agentIds;

    /** Noms des participants dans l'ordre du classement. */
    @Column(length = 1000)
    private String ranking;

    private String winner;

    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PriceModel model;

    private int paths;

    private int horizonDays;

    private long seed;

    private boolean antithetic;

    @Column(length = 1000)
    private String conclusion;

    private long elapsedMs;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
