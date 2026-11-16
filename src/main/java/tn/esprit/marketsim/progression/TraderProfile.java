package tn.esprit.marketsim.progression;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.UserLevel;

/** Portefeuille du jeu de trading et statistiques de progression d'un utilisateur (une ligne par utilisateur). */
@Entity
@Table(name = "trader_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraderProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    
    /**
     * COPIE du solde unique ({@code users.virtual_balance}), jamais de l'argent : rafraichie par {@link WalletService}
     * et lue seulement par les regles de niveau.
     */
    @Column(nullable = false)
    private double balance;

    /**
     * Ancien solde du jeu (base 1 000 $) avant la fusion des portefeuilles, garde pour l'historique. Null pour les
     * comptes crees apres la fusion. Voir {@link WalletMigration}.
     */
    private Double legacyGameBalance;

    /** Meilleur solde atteint depuis le dernier depart. */
    private double peakBalance;

    private int games;
    private int trades;
    private int wins;
    private double grossWin;
    private double grossLoss;
    private int disciplinedTrades;
    private double sumMaxDrawdown;

    /** Nombre de fois ou le trader a ete ruine et a recommence. */
    private int restarts;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserLevel level;

    /** Meilleur niveau jamais atteint (meme apres une ruine). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserLevel bestLevel;

    private Instant updatedAt;

    @Version
    private long version;

    public ProgressionRules.Stats stats() {
        return new ProgressionRules.Stats(balance, games, trades, wins, grossWin, grossLoss, disciplinedTrades,
                sumMaxDrawdown);
    }

    public void setStats(ProgressionRules.Stats s) {
        balance = s.balance();
        games = s.games();
        trades = s.trades();
        wins = s.wins();
        grossWin = s.grossWin();
        grossLoss = s.grossLoss();
        disciplinedTrades = s.disciplinedTrades();
        sumMaxDrawdown = s.sumMaxDrawdown();
        peakBalance = Math.max(peakBalance, s.balance());
    }
}
