package tn.esprit.marketsim.analysis;

import java.util.Locale;

/**
 * Module 3 : parametres attendus par chaque type de strategie, valeurs par defaut et validation.
 *
 * <pre>
 *   SMA_CROSS     fastPeriod (20), slowPeriod (50)
 *   EMA_CROSS     fastPeriod (12), slowPeriod (26)
 *   RSI           period (14), buyBelow (30), sellAbove (70)
 *   MACD          fastPeriod (12), slowPeriod (26), signalPeriod (9)
 *   BOLLINGER     period (20), bandK (2)
 *   BUY_AND_HOLD  aucun parametre
 * </pre>
 * Une {@link IllegalArgumentException} devient une reponse HTTP 400 (GlobalExceptionHandler).
 */
public final class StrategyRules {

    static final int MIN_PERIOD = 2;
    static final int MAX_PERIOD = 500;
    static final double MAX_BAND_K = 10;

    private StrategyRules() {
    }

    /**
     * Renvoie des parametres complets et valides pour ce type : les valeurs absentes prennent la valeur
     * par defaut, les champs qui ne concernent pas ce type sont effaces.
     */
    public static StrategyParams normalize(StrategyType type, StrategyParams raw) {
        StrategyParams in = raw == null ? new StrategyParams() : raw;
        StrategyParams out = new StrategyParams();
        switch (type) {
            case SMA_CROSS -> {
                out.setFastPeriod(period("fastPeriod", in.getFastPeriod(), 20));
                out.setSlowPeriod(period("slowPeriod", in.getSlowPeriod(), 50));
                checkFastBeforeSlow(out);
            }
            case EMA_CROSS -> {
                out.setFastPeriod(period("fastPeriod", in.getFastPeriod(), 12));
                out.setSlowPeriod(period("slowPeriod", in.getSlowPeriod(), 26));
                checkFastBeforeSlow(out);
            }
            case RSI -> {
                out.setPeriod(period("period", in.getPeriod(), 14));
                out.setBuyBelow(in.getBuyBelow() == null ? 30.0 : in.getBuyBelow());
                out.setSellAbove(in.getSellAbove() == null ? 70.0 : in.getSellAbove());
                // ecriture en "!(...)" pour refuser aussi NaN
                if (!(out.getBuyBelow() > 0 && out.getBuyBelow() < out.getSellAbove() && out.getSellAbove() < 100)) {
                    throw new IllegalArgumentException(
                            "RSI : les seuils doivent vérifier 0 < buyBelow < sellAbove < 100 (ex. 30 et 70)");
                }
            }
            case MACD -> {
                out.setFastPeriod(period("fastPeriod", in.getFastPeriod(), 12));
                out.setSlowPeriod(period("slowPeriod", in.getSlowPeriod(), 26));
                out.setSignalPeriod(period("signalPeriod", in.getSignalPeriod(), 9));
                checkFastBeforeSlow(out);
            }
            case BOLLINGER -> {
                out.setPeriod(period("period", in.getPeriod(), 20));
                out.setBandK(in.getBandK() == null ? 2.0 : in.getBandK());
                if (!(out.getBandK() > 0 && out.getBandK() <= MAX_BAND_K)) {
                    throw new IllegalArgumentException(
                            "bandK : le multiplicateur doit être supérieur à 0 et au plus " + (int) MAX_BAND_K);
                }
            }
            case BUY_AND_HOLD -> {
                // aucun parametre
            }
        }
        return out;
    }

    /** Texte court affiche dans la liste des strategies, ex : {@code "SMA(20),SMA(50)"}. */
    public static String describe(StrategyType type, StrategyParams p) {
        return switch (type) {
            case SMA_CROSS -> "SMA(" + p.getFastPeriod() + "),SMA(" + p.getSlowPeriod() + ")";
            case EMA_CROSS -> "EMA(" + p.getFastPeriod() + "),EMA(" + p.getSlowPeriod() + ")";
            case RSI -> "RSI(" + p.getPeriod() + ")";
            case MACD -> "MACD(" + p.getFastPeriod() + "," + p.getSlowPeriod() + "," + p.getSignalPeriod() + ")";
            case BOLLINGER -> "BB(" + p.getPeriod() + "," + number(p.getBandK()) + ")";
            case BUY_AND_HOLD -> "Buy & Hold";
        };
    }

    private static int period(String name, Integer value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value < MIN_PERIOD || value > MAX_PERIOD) {
            throw new IllegalArgumentException(
                    name + " : la période doit être un entier entre " + MIN_PERIOD + " et " + MAX_PERIOD);
        }
        return value;
    }

    private static void checkFastBeforeSlow(StrategyParams p) {
        if (p.getFastPeriod() >= p.getSlowPeriod()) {
            throw new IllegalArgumentException(
                    "La période rapide (fastPeriod) doit être plus courte que la période lente (slowPeriod)");
        }
    }

    /** 2.0 -> "2", 2.5 -> "2.5". */
    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%s", v);
    }
}
