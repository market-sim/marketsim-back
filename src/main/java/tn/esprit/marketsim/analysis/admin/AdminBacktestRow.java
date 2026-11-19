package tn.esprit.marketsim.analysis.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.admin.AdminRows.Owner;

/**
 * Module 3, administration : backtest de n'importe quel utilisateur, sans courbes ni trades. Rempli directement
 * par les requetes de {@link AdminBacktestQueries}. Taux en fractions.
 */
public record AdminBacktestRow(Long id, Long ownerId, String ownerFirstName, String ownerLastName, String ownerEmail,
                               Long strategyId, String strategyName, StrategyType strategyType, String symbol,
                               LocalDate from, LocalDate to, LocalDateTime createdAt, Double totalReturn,
                               Double annualizedReturn, Double sharpe, Double maxDrawdown, Integer tradeCount,
                               Double buyHoldReturn) {

    public Owner owner() {
        return Owner.of(ownerId, ownerFirstName, ownerLastName, ownerEmail);
    }
}
