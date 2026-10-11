package tn.esprit.marketsim.agent.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.engine.PriceModel;

/** Tournoi : plusieurs agents (+ buy &amp; hold) s'affrontent sur les memes futurs simules. */
public final class TournamentDtos {

    private TournamentDtos() {
    }

    public record TournamentRequest(@NotEmpty List<Long> agentIds, @NotBlank String symbol, Integer paths,
                                    Integer horizonDays, PriceModel model, Long seed, Boolean antithetic) {
    }

    /**
     * Resultats d'un concurrent sur l'ensemble des trajectoires.
     *
     * @param agentId  null pour le buy &amp; hold (reference)
     * @param q05..q95 quantiles du rendement final (pour la boite a moustaches)
     */
    public record Competitor(int rank, Long agentId, String name, AgentStrategy strategy, int riskLevel,
                             double meanReturn, double medianReturn, double stdReturn, double var95, double cvar95,
                             double probProfit, double meanSharpe, double meanMaxDrawdown, double avgTrades,
                             double q05, double q25, double q50, double q75, double q95) {
    }

    /** Courbe mediane (base 100) de chaque concurrent ; values[i] = concurrent i (meme ordre que le classement). */
    public record EquityPoint(int day, double[] values) {
    }

    /**
     * Comparaison appariee A vs B sur une metrique (memes trajectoires pour les deux).
     *
     * @param meanDiff        moyenne de (A - B) par trajectoire
     * @param ciLow           borne basse de l'IC 95 % bootstrap de meanDiff
     * @param tStat           statistique du test t apparie
     * @param pValue          p-value du test t (bilateral)
     * @param pHolm           p-value corrigee de Holm-Bonferroni (comparaisons multiples)
     * @param pBootstrap      p-value bootstrap (bilaterale)
     * @param pSign           p-value du test des signes (non parametrique)
     * @param winRate         part des trajectoires ou A fait mieux que B
     * @param cohenD          taille d'effet de Cohen (meanDiff / ecart-type des differences)
     * @param varianceFactor  gain de variance grace aux nombres aleatoires communs
     *                        (variance si echantillons independants / variance appariee)
     */
    public record PairwiseTest(int a, int b, String nameA, String nameB, double meanDiff, double ciLow,
                               double ciHigh, double tStat, double pValue, double pHolm, double pBootstrap,
                               double pSign, double winRate, double cohenD, double varianceFactor,
                               boolean significant, String verdict) {
    }

    public record TournamentResult(Long runId, String symbol, PriceModel model, int paths, int horizonDays,
                                   long seed, boolean antithetic, long elapsedMs, int bootstrapSamples,
                                   List<Competitor> ranking, List<EquityPoint> medianEquity,
                                   List<PairwiseTest> returnTests, List<PairwiseTest> sharpeTests,
                                   String conclusion) {
    }
}
