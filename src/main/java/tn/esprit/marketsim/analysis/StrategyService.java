package tn.esprit.marketsim.analysis;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.backtest.BacktestRepository;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;

@Service
@RequiredArgsConstructor
public class StrategyService {

    private final StrategyRepository repository;
    private final BacktestRepository backtests;

    public List<Strategy> findMine(User user) {
        return repository.findByOwnerId(user.getId());
    }

    public Strategy findOne(Long id, User user) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotFoundException("Strategie introuvable"));
    }

    public Strategy create(Strategy strategy, User user) {
        strategy.setId(null);
        strategy.setOwner(user);
        applyType(strategy, strategy.getType(), strategy.getParams(), strategy.getIndicators());
        return repository.save(strategy);
    }

    public Strategy update(Long id, Strategy data, User user) {
        Strategy strategy = findOne(id, user);
        strategy.setName(data.getName());
        strategy.setDescription(data.getDescription());
        applyType(strategy, data.getType(), data.getParams(), data.getIndicators());
        if (data.getStatus() != null) {
            strategy.setStatus(data.getStatus());
        }
        return repository.save(strategy);
    }

    /** Supprime la strategie et tous ses backtests. */
    @Transactional
    public void delete(Long id, User user) {
        Strategy strategy = findOne(id, user);
        backtests.deleteByStrategyId(strategy.getId());
        repository.delete(strategy);
    }

    /**
     * Enregistre dans la strategie le resume de son dernier backtest (ou le vide s'il n'y en a plus).
     *
     * @param winRatePct     taux de trades gagnants en pourcentage (null si aucun trade)
     * @param maxDrawdownPct drawdown maximum en pourcentage (valeur negative)
     */
    public void recordBacktest(Strategy strategy, Double sharpe, Double winRatePct, Double maxDrawdownPct,
                               Integer trades) {
        strategy.setSharpe(sharpe);
        strategy.setWinRate(winRatePct);
        strategy.setMaxDrawdown(maxDrawdownPct);
        strategy.setTrades(trades);
        repository.save(strategy);
    }

    /**
     * Avec un type : les parametres sont valides et completes, et le texte "indicators" est regenere.
     * Sans type (anciennes strategies) : pas de parametres, le texte libre est garde tel quel.
     */
    private static void applyType(Strategy strategy, StrategyType type, StrategyParams params, String indicators) {
        if (type == null) {
            if (hasValues(params)) {
                throw new IllegalArgumentException("Choisissez un type de stratégie (type) pour utiliser des paramètres");
            }
            strategy.setType(null);
            strategy.setParams(null);
            strategy.setIndicators(indicators);
            return;
        }
        StrategyParams valid = StrategyRules.normalize(type, params);
        strategy.setType(type);
        strategy.setParams(valid);
        strategy.setIndicators(StrategyRules.describe(type, valid));
    }

    private static boolean hasValues(StrategyParams p) {
        return p != null && (p.getFastPeriod() != null || p.getSlowPeriod() != null || p.getSignalPeriod() != null
                || p.getPeriod() != null || p.getBuyBelow() != null || p.getSellAbove() != null || p.getBandK() != null);
    }
}
