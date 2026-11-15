package tn.esprit.marketsim.analysis.backtest;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tn.esprit.marketsim.analysis.StrategyType;

/**
 * Module 3 : resume d'un backtest pour la liste (sans les courbes ni les trades, qui sont volumineux).
 * Rempli directement par la requete de {@link BacktestRepository#summaries}. Taux en fractions.
 */
public record BacktestSummary(Long id, Long strategyId, String strategyName, StrategyType strategyType,
                              String symbol, LocalDate from, LocalDate to, LocalDate splitDate,
                              LocalDateTime createdAt, Double totalReturn, Double annualizedReturn, Double sharpe,
                              Double maxDrawdown, Integer tradeCount, Double winRate, Double buyHoldReturn,
                              Double excessReturn) {
}
