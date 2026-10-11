package tn.esprit.marketsim.agent.engine;

import tn.esprit.marketsim.agent.AgentAction;

/**
 * Fait trader une politique sur une trajectoire de prix (long ou a plat), avec frais,
 * dimensionnement selon le risque et stop-loss / take-profit adaptes a la volatilite.
 */
public final class Backtester {

    /** Frais de transaction : 0,1 % par ordre. */
    public static final double COST = 0.001;

    /** Evenements enregistres par jour dans une trace (mode replay). */
    public static final int NONE = 0;
    public static final int ENTER = 1;
    public static final int EXIT = 2;
    public static final int STOP_LOSS = 3;
    public static final int TAKE_PROFIT = 4;

    private Backtester() {
    }

    /**
     * @param equity          valeur du portefeuille chaque jour (index 0 = debut de la simulation)
     * @param totalReturn     rendement final de l'agent
     * @param benchmarkReturn rendement du buy &amp; hold sur la meme trajectoire
     * @param exposure        part des jours ou l'agent etait investi
     * @param trace           detail jour par jour (null sauf en mode replay)
     */
    public record Result(double[] equity, double totalReturn, double benchmarkReturn, double maxDrawdown,
                         double sharpe, int trades, int wins, double exposure, Trace trace) {
    }

    /** Detail jour par jour : evenement, position apres decision, action voulue et confiance de la politique. */
    public record Trace(int[] events, int[] positions, AgentAction[] actions, double[] confidence) {
    }

    /** Fraction du capital engagee par position selon l'appetit pour le risque. */
    public static double positionFraction(double risk) {
        return 0.3 + 0.7 * risk;
    }

    /** Distance du stop-loss en % : ~2,5 ecarts-types sur 10 jours pour un agent prudent, 1 pour un agressif. */
    public static double stopDistance(double risk, double dailySigma) {
        return (2.5 - 1.5 * risk) * dailySigma * Math.sqrt(10);
    }

    public static Result run(double[] prices, int start, TradingPolicy policy, double risk,
                             double normalSigma, double capital) {
        return run(prices, start, policy, risk, normalSigma, capital, false);
    }

    /**
     * @param prices      trajectoire complete (historique + periode simulee)
     * @param start       premier jour de trading (les jours precedents servent aux indicateurs)
     * @param normalSigma volatilite annuelle de reference de l'actif
     * @param record      enregistrer la trace jour par jour (replay)
     */
    public static Result run(double[] prices, int start, TradingPolicy policy, double risk,
                             double normalSigma, double capital, boolean record) {
        int days = prices.length - start;
        double[] equity = new double[days];
        Trace trace = record
                ? new Trace(new int[days], new int[days], new AgentAction[days], new double[days])
                : null;
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
            int day = t - start;
            double price = prices[t];
            int event = NONE;

            if (units > 0 && (price <= entry * (1 - stop) || price >= entry * (1 + 2 * stop))) {
                event = price <= entry * (1 - stop) ? STOP_LOSS : TAKE_PROFIT;
                cash += units * price * (1 - COST);
                if (price > entry) wins++;
                trades++;
                units = 0;
            }

            if (event == NONE || record) {
                MarketFeatures f = MarketFeatures.compute(prices, t, normalSigma);
                TradingPolicy.PolicyDecision decision = policy.decide(f, units > 0 ? 1 : 0);
                if (record) {
                    trace.actions()[day] = decision.action();
                    trace.confidence()[day] = decision.confidence();
                }
                if (event == NONE) {
                    if (decision.action() == AgentAction.BUY && units == 0) {
                        double notional = cash * fraction;
                        units = notional * (1 - COST) / price;
                        cash -= notional;
                        entry = price;
                        stop = stopDistance(risk, dailySigma);
                        event = ENTER;
                    } else if (decision.action() == AgentAction.SELL && units > 0) {
                        cash += units * price * (1 - COST);
                        if (price > entry) wins++;
                        trades++;
                        units = 0;
                        event = EXIT;
                    }
                }
            }

            if (units > 0) investedDays++;
            equity[day] = cash + units * price;
            if (record) {
                trace.events()[day] = event;
                trace.positions()[day] = units > 0 ? 1 : 0;
            }
        }

        double totalReturn = equity[days - 1] / capital - 1;
        double benchmark = prices[prices.length - 1] / prices[start] - 1;
        return new Result(equity, totalReturn, benchmark, Stats.maxDrawdown(equity),
                Stats.sharpe(equity), trades, wins, (double) investedDays / days, trace);
    }
}
