package tn.esprit.marketsim.agent.engine;

import java.util.List;

import tn.esprit.marketsim.agent.AgentAction;

/** Cerveau de l'agent : observe le marche et sa position, renvoie une decision expliquee. */
public interface TradingPolicy {

    /**
     * @param features indicateurs de marche a l'instant t
     * @param position 0 = pas de position, 1 = position longue ouverte
     */
    PolicyDecision decide(MarketFeatures features, int position);

    /** Contribution d'une variable a la decision (explication XAI). */
    record Contribution(String feature, String label, double value, double contribution) {
    }

    /**
     * @param score         score brut (lineaire) ou avantage de l'action choisie (Q-learning)
     * @param confidence    confiance : [0.5, 1] (lineaire) ou probabilite softmax [1/3, 1] (Q-learning)
     * @param contributions explication : apport signe de chaque variable
     * @param qValues       valeurs Q par action (HOLD, BUY, SELL) ou null pour les modeles lineaires
     */
    record PolicyDecision(AgentAction action, double score, double confidence,
                          List<Contribution> contributions, double[] qValues) {
    }
}
