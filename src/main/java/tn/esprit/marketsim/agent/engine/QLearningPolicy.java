package tn.esprit.marketsim.agent.engine;

import java.util.List;

import tn.esprit.marketsim.agent.AgentAction;

/**
 * Politique gloutonne issue d'une Q-table apprise.
 *
 * <p>XAI par <b>occlusion</b> : pour chaque variable, on remplace sa valeur par la valeur neutre
 * et on mesure combien l'avantage de l'action choisie diminue. Une contribution positive
 * signifie "cette variable pousse l'agent vers cette decision".
 */
public final class QLearningPolicy implements TradingPolicy {

    private final QTable table;

    public QLearningPolicy(QTable table) {
        this.table = table;
    }

    @Override
    public PolicyDecision decide(MarketFeatures f, int position) {
        int trend = QTable.trendBucket(f);
        int rsi = QTable.rsiBucket(f);
        int vol = QTable.volBucket(f);
        int s = QTable.state(trend, rsi, vol, position);
        int a = table.best(s);
        double[] qValues = table.values(s).clone();
        double adv = table.advantage(s, a);

        List<Contribution> contributions = List.of(
                new Contribution("trend", "Tendance (MM10/MM30)", f.trend(),
                        adv - table.advantage(QTable.state(1, rsi, vol, position), a)),
                new Contribution("rsi", "RSI 14", f.rsi(),
                        adv - table.advantage(QTable.state(trend, 1, vol, position), a)),
                new Contribution("volatility", "Volatilité", f.volatility(),
                        adv - table.advantage(QTable.state(trend, rsi, 0, position), a)),
                new Contribution("position", "Position actuelle", position,
                        adv - table.advantage(QTable.state(trend, rsi, vol, 0), a)));

        // confiance = probabilite softmax (temperature 1) de l'action choisie, dans [1/3, 1]
        double max = qValues[a];
        double sum = 0;
        for (double v : qValues) sum += Math.exp(v - max);
        double confidence = 1 / sum;

        return new PolicyDecision(AgentAction.values()[a], adv, confidence, contributions, qValues);
    }
}
