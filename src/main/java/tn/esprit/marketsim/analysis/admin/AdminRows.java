package tn.esprit.marketsim.analysis.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestDetail;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.ComparisonDetail;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.StrategySnapshot;
import tn.esprit.marketsim.analysis.comparison.ComparisonRanking;

/** Module 3, administration : lignes lues en base et objets renvoyes au front. Taux en fractions. */
public final class AdminRows {

    private AdminRows() {
    }

    /** Proprietaire d'un resultat. */
    public record Owner(Long id, String name, String email) {

        static Owner of(Long id, String firstName, String lastName, String email) {
            String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).strip();
            return new Owner(id, name.isEmpty() ? email : name, email);
        }
    }

    /** Ligne de la liste des backtests de l'administration. */
    public record BacktestItem(Long id, Owner owner, Long strategyId, String strategyName, StrategyType strategyType,
                               String symbol, LocalDate from, LocalDate to, LocalDateTime createdAt, Double totalReturn,
                               Double annualizedReturn, Double sharpe, Double maxDrawdown, Integer tradeCount,
                               Double buyHoldReturn) {

        static BacktestItem of(AdminBacktestRow r) {
            return new BacktestItem(r.id(), r.owner(), r.strategyId(), r.strategyName(), r.strategyType(), r.symbol(),
                    r.from(), r.to(), r.createdAt(), r.totalReturn(), r.annualizedReturn(), r.sharpe(),
                    r.maxDrawdown(), r.tradeCount(), r.buyHoldReturn());
        }
    }

    /** Ligne de la liste des comparaisons de l'administration (strategies comparees et leurs metriques). */
    public record ComparisonItem(Long id, Owner owner, String name, String symbol, LocalDate from, LocalDate to,
                                 LocalDateTime createdAt, ComparisonRanking rankBy, Double buyHoldReturn,
                                 List<StrategySnapshot> strategies) {
    }

    /** Une page de resultats ({@code total} : nombre de lignes pour l'ensemble des filtres). */
    public record ResultPage<T>(List<T> items, long total, int page, int size) {
    }

    /** Detail en lecture seule, avec son proprietaire. */
    public record BacktestView(Owner owner, BacktestDetail backtest) {
    }

    public record ComparisonView(Owner owner, ComparisonDetail comparison) {
    }

    /** Activite d'un utilisateur : nombre de resultats, Sharpe moyen de ses backtests, derniere activite. */
    public record UserActivity(Owner owner, int backtests, int comparisons, Double averageSharpe,
                               LocalDateTime lastActivity) {
    }
}
