package tn.esprit.marketsim.agent;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.LinearPolicy;
import tn.esprit.marketsim.agent.engine.QLearningPolicy;
import tn.esprit.marketsim.agent.engine.QTable;
import tn.esprit.marketsim.agent.engine.TradingPolicy;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.market.AssetRepository;

/** Outils partages par les services du module 6. */
@Component
@RequiredArgsConstructor
public class AgentLabSupport {

    private final AssetRepository assetRepository;

    /** Actif du module 5 a partir de son symbole. */
    public Asset asset(String symbol) {
        return assetRepository.findAll().stream()
                .filter(a -> a.getSymbol().equalsIgnoreCase(symbol.trim()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Actif inconnu : " + symbol));
    }

    public AssetDynamics dynamics(Asset asset) {
        return AssetDynamics.of(asset.getType());
    }

    /** Construit le "cerveau" de l'agent selon sa strategie. */
    public TradingPolicy policy(AgentConfig agent) {
        if (agent.getStrategy() == AgentStrategy.Q_LEARNING) {
            if (!agent.isTrained()) {
                throw new IllegalArgumentException("Agent Q-learning non entraîné : lancez d'abord l'entraînement");
            }
            return new QLearningPolicy(QTable.parse(agent.getQTable()));
        }
        return LinearPolicy.of(agent.getStrategy(), agent.risk());
    }
}
