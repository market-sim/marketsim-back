package tn.esprit.marketsim.agent.engine;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import tn.esprit.marketsim.agent.AgentAction;

/**
 * Q-table d'un agent Q-learning.
 *
 * <p>Etat discret (36 etats) = tendance (3) x RSI (3) x volatilite (2) x position (2).
 * Actions : HOLD, BUY (ouvrir/garder une position longue), SELL (fermer / rester a plat).
 */
public final class QTable {

    public static final int STATES = 36;
    public static final int ACTIONS = AgentAction.values().length;

    static final String[] TREND = {"Baissière", "Neutre", "Haussière"};
    static final String[] RSI = {"Survente", "Neutre", "Surachat"};
    static final String[] VOL = {"Calme", "Agitée"};
    static final String[] POSITION = {"À plat", "Investi"};

    final double[][] q;

    public QTable() {
        q = new double[STATES][ACTIONS];
    }

    private QTable(double[][] q) {
        this.q = q;
    }

    // ----- Discretisation -----

    public static int trendBucket(MarketFeatures f) {
        return f.trend() < -0.25 ? 0 : f.trend() > 0.25 ? 2 : 1;
    }

    public static int rsiBucket(MarketFeatures f) {
        return f.rsi() < -0.3 ? 0 : f.rsi() > 0.3 ? 2 : 1;
    }

    public static int volBucket(MarketFeatures f) {
        return f.volatility() > 0.2 ? 1 : 0;
    }

    public static int state(int trend, int rsi, int vol, int position) {
        return ((trend * 3 + rsi) * 2 + vol) * 2 + position;
    }

    public static int state(MarketFeatures f, int position) {
        return state(trendBucket(f), rsiBucket(f), volBucket(f), position);
    }

    /** Decode un indice d'etat en {tendance, rsi, vol, position}. */
    public static int[] decode(int s) {
        int position = s % 2;
        int vol = (s / 2) % 2;
        int rsi = (s / 4) % 3;
        int trend = s / 12;
        return new int[] {trend, rsi, vol, position};
    }

    public static String describe(int s) {
        int[] d = decode(s);
        return "Tendance " + TREND[d[0]] + " · RSI " + RSI[d[1]] + " · Vol. " + VOL[d[2]] + " · " + POSITION[d[3]];
    }

    // ----- Acces -----

    public double[] values(int s) {
        return q[s];
    }

    public int best(int s) {
        int best = 0;
        for (int a = 1; a < ACTIONS; a++) {
            if (q[s][a] > q[s][best]) best = a;
        }
        return best;
    }

    public double max(int s) {
        return q[s][best(s)];
    }

    /** Avantage de l'action a : Q(s,a) - moyenne des Q(s,.). */
    public double advantage(int s, int a) {
        return q[s][a] - Arrays.stream(q[s]).average().orElse(0);
    }

    public void update(int s, int a, double target, double alpha) {
        q[s][a] += alpha * (target - q[s][a]);
    }

    // ----- Persistance (CSV dans AgentConfig.qTable) -----

    public String serialize() {
        return Arrays.stream(q)
                .flatMapToDouble(Arrays::stream)
                .mapToObj(v -> String.format(Locale.ROOT, "%.6f", v))
                .collect(Collectors.joining(","));
    }

    public static QTable parse(String csv) {
        String[] parts = csv.split(",");
        if (parts.length != STATES * ACTIONS) {
            throw new IllegalArgumentException("Q-table corrompue");
        }
        double[][] q = new double[STATES][ACTIONS];
        for (int i = 0; i < parts.length; i++) {
            q[i / ACTIONS][i % ACTIONS] = Double.parseDouble(parts[i]);
        }
        return new QTable(q);
    }
}
