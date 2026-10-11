package tn.esprit.marketsim.agent.engine;

import java.util.ArrayList;
import java.util.List;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentStrategy;

/**
 * Modele de score lineaire : score = somme(w_i * x_i).
 *
 * <p>XAI : avec une reference x = 0 (marche neutre), la valeur de Shapley de chaque variable
 * d'un modele lineaire vaut exactement w_i * x_i. Les contributions renvoyees sont donc des
 * <b>valeurs SHAP exactes</b> (pas une approximation) et leur somme est egale au score.
 */
public final class LinearPolicy implements TradingPolicy {

    //                                       momentum trend  rsi   meanRev volatility
    private static final double[] MOMENTUM = {1.00, 0.80, 0.25, 0.00, -0.50};
    private static final double[] MEAN_REVERSION = {-0.20, 0.00, -0.70, 1.00, -0.50};

    private final double[] weights;
    private final double threshold;

    private LinearPolicy(double[] weights, double threshold) {
        this.weights = weights;
        this.threshold = threshold;
    }

    /**
     * Plus l'agent est prudent (risk -> 0), plus il penalise la volatilite
     * et plus il exige un signal fort avant d'agir.
     */
    public static LinearPolicy of(AgentStrategy strategy, double risk) {
        double[] base = strategy == AgentStrategy.MEAN_REVERSION ? MEAN_REVERSION : MOMENTUM;
        double[] w = base.clone();
        w[4] = base[4] * (1.5 - risk);
        double threshold = 0.35 - 0.20 * risk;
        return new LinearPolicy(w, threshold);
    }

    @Override
    public PolicyDecision decide(MarketFeatures features, int position) {
        double[] x = features.toArray();
        List<Contribution> contributions = new ArrayList<>();
        double score = 0;
        for (int i = 0; i < x.length; i++) {
            double c = weights[i] * x[i];
            score += c;
            contributions.add(new Contribution(MarketFeatures.NAMES[i], MarketFeatures.LABELS[i], x[i], c));
        }

        AgentAction action = score > threshold ? AgentAction.BUY
                : score < -threshold ? AgentAction.SELL
                : AgentAction.HOLD;

        double confidence;
        if (action == AgentAction.HOLD) {
            // confiant de ne rien faire quand le score est proche de 0
            confidence = 1 - 0.5 * Math.abs(score) / threshold;
        } else {
            double pUp = 1 / (1 + Math.exp(-1.5 * score));
            confidence = Math.max(pUp, 1 - pUp);
        }
        return new PolicyDecision(action, score, confidence, contributions, null);
    }
}
