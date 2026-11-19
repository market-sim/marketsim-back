package tn.esprit.marketsim.analysis.admin;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.backtest.Backtest;

/**
 * Module 3, administration : lectures des backtests de tous les utilisateurs, sans charger le JSON volumineux.
 * A part de {@code BacktestRepository} (qui filtre toujours par proprietaire) : ces requetes ne servent qu'au
 * service d'administration, dont les routes sont reservees a l'ADMIN.
 */
public interface AdminBacktestQueries extends Repository<Backtest, Long> {

    String ROW = """
            new tn.esprit.marketsim.analysis.admin.AdminBacktestRow(
                b.id, o.id, o.firstName, o.lastName, o.email, b.strategy.id, b.strategyName, b.strategyType, b.symbol,
                b.fromDate, b.toDate, b.createdAt, b.totalReturn, b.annualizedReturn, b.sharpe, b.maxDrawdown, b.trades,
                b.buyHoldReturn)""";

    // chaine ordinaire et non bloc de texte : un bloc retirerait l'espace initial, qui separe « o » de « where »
    String FILTER = " where (:ownerId is null or o.id = :ownerId)"
            + " and (:symbol is null or b.symbol = :symbol)"
            + " and (:type is null or b.strategyType = :type)";

    /** Une page de backtests, les plus recents d'abord ; chaque filtre est facultatif (null = tous). */
    @Query(value = "select " + ROW + " from Backtest b join b.owner o" + FILTER + " order by b.createdAt desc, b.id desc",
            countQuery = "select count(b) from Backtest b join b.owner o" + FILTER)
    Page<AdminBacktestRow> page(@Param("ownerId") Long ownerId, @Param("symbol") String symbol,
                                @Param("type") StrategyType type, Pageable pageable);

    /** Tous les backtests (statistiques et activite des utilisateurs). */
    @Query("select " + ROW + " from Backtest b join b.owner o order by b.createdAt desc, b.id desc")
    List<AdminBacktestRow> all();

    /** Un backtest (pour connaitre son proprietaire). */
    @Query("select " + ROW + " from Backtest b join b.owner o where b.id = :id")
    Optional<AdminBacktestRow> row(@Param("id") Long id);
}
