package tn.esprit.marketsim.progression.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.progression.HumanSimulator;
import tn.esprit.marketsim.progression.ProgressionRules.Difficulty;
import tn.esprit.marketsim.progression.ProgressionRules.Goal;
import tn.esprit.marketsim.user.UserLevel;

public final class ProgressionDtos {

    private ProgressionDtos() {
    }

    /**
     * Etat du trader : portefeuille, niveau, statistiques, conditions du niveau suivant et difficulte actuelle.
     *
     * @param bankrupt vrai si le solde ne permet plus de jouer (il faut recommencer au capital de depart)
     */
    public record ProgressionResponse(
            double balance,
            double startBalance,
            double peakBalance,
            UserLevel level,
            UserLevel bestLevel,
            int skillScore,
            int games,
            int trades,
            double winRate,
            double profitFactor,
            double discipline,
            double avgMaxDrawdown,
            int restarts,
            boolean bankrupt,
            UserLevel nextLevel,
            List<Goal> nextGoals,
            Difficulty difficulty,
            Instant updatedAt) {
    }

    public record OrderDto(@NotNull Integer day, @NotNull HumanSimulator.Side side, Double fraction) {
    }

    /** Parametres de la partie (les memes que ceux du replay) et ordres du joueur ; le serveur recalcule tout. */
    public record SettleGameRequest(
            @NotNull Long agentId,
            @NotBlank String symbol,
            @NotNull Integer days,
            PriceModel model,
            @NotNull Long seed,
            Double crashPct,
            @NotNull @Size(max = 2000) List<@Valid OrderDto> orders) {
    }

    /**
     * @param counted faux si ce marche avait deja ete joue (entrainement : le portefeuille ne change pas)
     */
    public record SettleGameResponse(
            boolean counted,
            double startBalance,
            double endBalance,
            double pnl,
            int trades,
            int wins,
            double maxDrawdown,
            double fees,
            UserLevel levelBefore,
            UserLevel levelAfter,
            boolean levelUp,
            boolean levelDown,
            String message,
            ProgressionResponse progression) {
    }

    /**
     * @param playerReturn    rendement du joueur sur la partie
     * @param agentReturn     rendement de l'agent sur le meme marche (null pour les anciennes parties)
     * @param benchmarkReturn rendement de « acheter et garder »
     */
    public record GameHistoryItem(Long id, String symbol, int days, double startBalance, double endBalance,
                                  int trades, int wins, double maxDrawdown, UserLevel levelBefore,
                                  UserLevel levelAfter, Instant playedAt, double playerReturn, Double agentReturn,
                                  Double benchmarkReturn, boolean counted) {
    }

    /** Ligne du back-office : score de trading d'un utilisateur. */
    public record AdminTraderScore(Long userId, UserLevel level, UserLevel bestLevel, double balance, int skillScore,
                                   int games, int trades, double winRate, double discipline, boolean bankrupt,
                                   int restarts, Instant updatedAt) {
    }
}
