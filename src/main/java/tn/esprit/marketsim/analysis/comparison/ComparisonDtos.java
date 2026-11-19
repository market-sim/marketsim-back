package tn.esprit.marketsim.analysis.comparison;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.CompareRequest;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.ComparisonResult;

/** Module 3 : objets echanges avec le front pour les comparaisons enregistrees. Taux en fractions (0.05 = 5 %). */
public final class ComparisonDtos {

    private ComparisonDtos() {
    }

    /**
     * Demande d'enregistrement. {@code request} est la demande de comparaison existante (strategies, actif,
     * periode, reglages) : le serveur relance lui-meme le calcul. Aucun champ ne porte de resultat.
     *
     * @param rankBy critere de classement affiche : SHARPE (defaut), ANNUALIZED_RETURN ou MAX_DRAWDOWN.
     *               En texte pour qu'une valeur inconnue donne un message clair plutot qu'un "JSON invalide".
     */
    public record SaveComparisonRequest(String name, String rankBy, CompareRequest request) {
    }

    /** Renommage d'une comparaison enregistree. */
    public record RenameRequest(String name) {
    }

    /** Etat actuel d'une strategie comparee, calcule a chaque lecture. */
    public enum StrategyStatus {
        /** Elle existe toujours, avec le meme type et les memes parametres qu'a l'enregistrement. */
        CURRENT,
        /** Elle existe toujours, mais son type ou ses parametres ont change : les resultats gardent les anciens. */
        MODIFIED,
        /** Elle a ete supprimee : la comparaison reste lisible grace a l'instantane. */
        DELETED
    }

    /**
     * Instantane d'une strategie au moment de l'enregistrement, avec ses metriques principales (pour la liste).
     *
     * @param strategyId identifiant d'origine (la strategie a pu etre supprimee depuis)
     */
    public record StrategySnapshot(Long strategyId, String name, StrategyType type, StrategyParams params,
                                   Double totalReturn, Double annualizedReturn, Double sharpe, Double maxDrawdown,
                                   Integer tradeCount) {
    }

    /**
     * Resume d'une comparaison enregistree, sans les courbes. {@code strategyStatus} est aligne sur
     * {@code strategies} (meme ordre que dans la comparaison).
     */
    public record ComparisonSummary(Long id, String name, String symbol, LocalDate from, LocalDate to,
                                    LocalDate splitDate, ComparisonRanking rankBy, LocalDateTime createdAt,
                                    String dataSource, String dataHash, Double buyHoldReturn,
                                    List<StrategySnapshot> strategies, List<StrategyStatus> strategyStatus) {
    }

    /**
     * Comparaison enregistree complete. {@code result} a exactement le format de la comparaison a la demande
     * ({@code POST /api/analysis/backtests/compare}) : le front l'affiche avec le meme ecran.
     * {@code strategyStatus} est aligne sur {@code result.strategies()}.
     */
    public record ComparisonDetail(Long id, String name, ComparisonRanking rankBy, LocalDateTime createdAt,
                                   List<StrategyStatus> strategyStatus, ComparisonResult result) {
    }
}
