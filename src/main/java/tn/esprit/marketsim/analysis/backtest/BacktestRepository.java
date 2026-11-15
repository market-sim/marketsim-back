package tn.esprit.marketsim.analysis.backtest;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BacktestRepository extends JpaRepository<Backtest, Long> {

    /** Resumes des backtests d'un utilisateur, du plus recent au plus ancien, sans charger le JSON volumineux. */
    @Query("""
            select new tn.esprit.marketsim.analysis.backtest.BacktestSummary(
                b.id, b.strategy.id, b.strategyName, b.strategyType, b.symbol, b.fromDate, b.toDate, b.splitDate,
                b.createdAt, b.totalReturn, b.annualizedReturn, b.sharpe, b.maxDrawdown, b.trades, b.winRate,
                b.buyHoldReturn, b.excessReturn)
            from Backtest b
            where b.owner.id = :ownerId
            order by b.createdAt desc, b.id desc
            """)
    List<BacktestSummary> summaries(@Param("ownerId") Long ownerId);

    Optional<Backtest> findByIdAndOwnerId(Long id, Long ownerId);

    /** Dernier backtest d'une strategie (pour remettre a jour son resume apres une suppression). */
    Optional<Backtest> findFirstByStrategyIdOrderByCreatedAtDescIdDesc(Long strategyId);

    void deleteByStrategyId(Long strategyId);
}
