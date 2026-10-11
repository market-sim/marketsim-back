package tn.esprit.marketsim.agent.engine;

import tn.esprit.marketsim.agent.AgentAction;

/**
 * Fait trader une politique sur une trajectoire de prix (long ou a plat), avec frais,
 * dimensionnement selon le risque et stop-loss / take-profit adaptes a la volatilite.
 */
public final class Backtester {

    /** Frais de transaction : 0,1 % par ordre. */
    public static final double COST = 0.001;

    private Backtester() {
    }

    /**
     * @param equity          valeur du portefeuille chaque jour (index 0 = debut de la simulation)
     * @param totalReturn     rendement final de l'agent
     * @param benchmarkReturn rendement du buy &amp; hold sur la meme trajectoire
     * @param exposure        part des jours ou l'agent etait investi
     */
    public record Result(double[] equity, double totalReturn, double benchmarkReturn, double maxDrawdown,
                         double sharpe, int trades, int wins, double exposure) {
    }

    /** Fraction du capital engagee par position selon l'appetit pour le risque. */
    public static double positionFraction(double risk) {
        return 0.3 + 0.7 * risk;
    }

    /** Distance du stop-loss en % : ~2,5 ecarts-types sur 10 jours pour un agent prudent, 1 pour un agressif. */
    public static double stopDistance(double risk, double dailySigma) {
        return (2.5 - 1.5 * risk) * dailySigma * Math.sqrt(10);
    }

    /**
     * @param prices      trajectoire complete (historique + periode simulee)
     * @param start       premier jour de trading (les jours precedents servent aux indicateurs)
     * @param normalSigma volatilite annuelle de reference de l'actif
     */
    public static Result run(double[] prices, int start, TradingPolicy policy, double risk,
                             double normalSigma, double capital) {
        int days = prices.length - start;
        double[] equity = new double[days];
        double fraction = positionFraction(risk);
        double dailySigma = normalSigma / Math.sqrt(PathGenerator.TRADING_DAYS);

        double cash = capital;
        double units = 0;
        double entry = 0;
        double stop = 0;
        int trades = 0;
        int wins = 0;
        int investedDays = 0;

        for (int t = start; t < prices.length; t++) {
            double price = prices[t];
            boolean stopped = false;

            if (units > 0 && (price <= entry * (1 - stop) || price >= entry * (1 + 2 * stop))) {
                cash += units * price * (1 - COST);
                if (price > entry) wins++;
                trades++;
                units = 0;
                stopped = true;
            }

            if (!stopped) {
                MarketFeatures f = MarketFeatures.compute(prices, t, normalSigma);
                AgentAction action = policy.decide(f, units > 0 ? 1 : 0).action();
                if (action == AgentAction.BUY && units == 0) {
                    double notional = cash * fraction;
                    units = notional * (1 - COST) / price;
                    cash -= notional;
                    entry = price;
                    stop = stopDistance(risk, dailySigma);
                } else if (action == AgentAction.SELL && units > 0) {
                    cash += units * price * (1 - COST);
                    if (price > entry) wins++;
                    trades++;
                    units = 0;
                }
            }

            if (units > 0) investedDays++;
            equity[t - start] = cash + units * price;
        }

        double totalReturn = equity[days - 1] / capital - 1;
        double benchmark = prices[prices.length - 1] / prices[start] - 1;
        return new Result(equity, totalReturn, benchmark, Stats.maxDrawdown(equity),
                Stats.sharpe(equity), trades, wins, (double) investedDays / days);
    }
}
