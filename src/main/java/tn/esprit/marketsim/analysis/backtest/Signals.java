package tn.esprit.marketsim.analysis.backtest;

import java.util.Arrays;

import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.indicator.Indicators;

/**
 * Module 3 : transforme une strategie a regles en un signal par jour.
 *
 * <p>Le signal du jour i est calcule avec les clotures des jours 0 a i uniquement (les indicateurs de
 * {@link Indicators} respectent deja cette regle). Le moteur l'execute a la cloture du jour i+1.
 *
 * <pre>
 *   SMA_CROSS / EMA_CROSS  achat le jour ou la moyenne rapide passe au-dessus de la lente,
 *                          vente le jour ou elle repasse en dessous
 *   MACD                   meme regle entre la ligne MACD et sa ligne de signal
 *   RSI                    achat si RSI &lt; buyBelow (survente), vente si RSI &gt; sellAbove (surachat)
 *   BOLLINGER              achat si la cloture est sous la bande basse, vente si elle est revenue
 *                          a la moyenne (retour a la moyenne)
 *   BUY_AND_HOLD           achat le premier jour, jamais de vente
 * </pre>
 * Tant qu'un indicateur n'a pas assez d'historique (NaN), le signal est HOLD.
 */
public final class Signals {

    private Signals() {
    }

    /**
     * @param params parametres complets (voir StrategyRules.normalize)
     * @param first  indice du premier jour de la periode testee (utile seulement au buy and hold)
     */
    public static Signal[] compute(StrategyType type, StrategyParams params, double[] closes, int first) {
        Signal[] out = new Signal[closes.length];
        Arrays.fill(out, Signal.HOLD);
        switch (type) {
            case SMA_CROSS -> crossings(out,
                    Indicators.sma(closes, params.getFastPeriod()), Indicators.sma(closes, params.getSlowPeriod()));
            case EMA_CROSS -> crossings(out,
                    Indicators.ema(closes, params.getFastPeriod()), Indicators.ema(closes, params.getSlowPeriod()));
            case MACD -> {
                Indicators.Macd m = Indicators.macd(closes, params.getFastPeriod(), params.getSlowPeriod(),
                        params.getSignalPeriod());
                crossings(out, m.macd(), m.signal());
            }
            case RSI -> {
                double[] rsi = Indicators.rsi(closes, params.getPeriod());
                for (int i = 0; i < closes.length; i++) {
                    // une comparaison avec NaN est fausse : le signal reste HOLD pendant la chauffe
                    if (rsi[i] < params.getBuyBelow()) out[i] = Signal.BUY;
                    else if (rsi[i] > params.getSellAbove()) out[i] = Signal.SELL;
                }
            }
            case BOLLINGER -> {
                Indicators.Bands b = Indicators.bollinger(closes, params.getPeriod(), params.getBandK());
                for (int i = 0; i < closes.length; i++) {
                    if (closes[i] < b.lower()[i]) out[i] = Signal.BUY;
                    else if (closes[i] >= b.middle()[i]) out[i] = Signal.SELL;
                }
            }
            case BUY_AND_HOLD -> out[first] = Signal.BUY;
        }
        return out;
    }

    /** Achat le jour ou la ligne a passe au-dessus de la ligne b, vente le jour ou elle repasse en dessous. */
    private static void crossings(Signal[] out, double[] a, double[] b) {
        for (int i = 1; i < out.length; i++) {
            if (Double.isNaN(a[i - 1]) || Double.isNaN(b[i - 1]) || Double.isNaN(a[i]) || Double.isNaN(b[i])) {
                continue;
            }
            if (a[i] > b[i] && a[i - 1] <= b[i - 1]) out[i] = Signal.BUY;
            else if (a[i] < b[i] && a[i - 1] >= b[i - 1]) out[i] = Signal.SELL;
        }
    }

    /** Nombre de clotures necessaires pour que la strategie puisse produire son premier signal. */
    public static int warmup(StrategyType type, StrategyParams params) {
        return switch (type) {
            // un croisement compare deux jours consecutifs : il faut la moyenne lente la veille et le jour meme
            case SMA_CROSS, EMA_CROSS -> params.getSlowPeriod() + 1;
            case MACD -> params.getSlowPeriod() + params.getSignalPeriod();
            case RSI -> params.getPeriod() + 1;
            case BOLLINGER -> params.getPeriod();
            case BUY_AND_HOLD -> 1;
        };
    }
}
