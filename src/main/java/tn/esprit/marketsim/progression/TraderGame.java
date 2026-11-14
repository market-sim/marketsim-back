package tn.esprit.marketsim.progression;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.UserLevel;

/** Une partie jouee et son effet sur le portefeuille (historique). */
@Entity
@Table(name = "trader_games", indexes = @Index(name = "idx_trader_games_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraderGame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 20)
    private String symbol;

    private long seed;
    private int days;
    private double startBalance;
    private double endBalance;
    private int trades;
    private int wins;
    private double maxDrawdown;
    private double fees;

    /** Rendement de l'agent sur le meme marche (pour comparer joueur et agent dans le temps). */
    private Double agentReturn;

    /** Rendement du simple « acheter et garder » sur le meme marche. */
    private Double benchmarkReturn;

    /** Partie comptee (faux = marche deja joue, entrainement). */
    private Boolean counted;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserLevel levelBefore;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserLevel levelAfter;

    private Instant playedAt;
}
