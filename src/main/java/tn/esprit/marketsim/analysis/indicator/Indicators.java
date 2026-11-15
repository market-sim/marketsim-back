package tn.esprit.marketsim.analysis.indicator;

import java.util.Arrays;

/**
 * Module 3 : indicateurs techniques calcules sur les cours de cloture.
 *
 * <p>Regles communes a toutes les fonctions :
 * <ul>
 *   <li>le tableau renvoye a la meme taille que le tableau de prix : la case i correspond au jour i ;</li>
 *   <li>la valeur du jour i n'utilise que les prix des jours 0 a i (aucun biais d'anticipation) ;</li>
 *   <li>tant qu'il n'y a pas assez d'historique (periode de chauffe), la valeur est {@code NaN}.</li>
 * </ul>
 * Fonctions pures, sans Spring ni base de donnees : faciles a tester et a expliquer.
 */
public final class Indicators {

    private Indicators() {
    }

    /** Lignes du MACD : macd = EMA rapide - EMA lente, signal = EMA du macd, histogramme = macd - signal. */
    public record Macd(double[] macd, double[] signal, double[] histogram) {
    }

    /** Bandes de Bollinger : milieu = SMA, haut et bas = milieu +/- k ecarts-types. */
    public record Bands(double[] upper, double[] middle, double[] lower) {
    }

    // ===================== Moyennes mobiles =====================

    /** Moyenne mobile simple : moyenne des n dernieres clotures. Premiere valeur au jour n-1. */
    public static double[] sma(double[] prices, int n) {
        checkPeriod(n);
        double[] out = nan(prices.length);
        for (int i = n - 1; i < prices.length; i++) {
            out[i] = mean(prices, i - n + 1, i);
        }
        return out;
    }

    /**
     * Moyenne mobile exponentielle : EMA(i) = a * prix(i) + (1 - a) * EMA(i-1), avec a = 2 / (n + 1).
     * Elle est initialisee au jour n-1 par la moyenne simple des n premiers prix.
     */
    public static double[] ema(double[] prices, int n) {
        return ema(prices, n, 0);
    }

    /** EMA d'une serie dont les valeurs ne commencent qu'a l'indice {@code start} (utile pour la ligne de signal du MACD). */
    private static double[] ema(double[] values, int n, int start) {
        checkPeriod(n);
        double[] out = nan(values.length);
        int first = start + n - 1;               // premier jour ou l'on dispose de n valeurs
        if (first >= values.length) {
            return out;
        }
        double alpha = 2.0 / (n + 1);
        out[first] = mean(values, start, first);
        for (int i = first + 1; i < values.length; i++) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    // ===================== RSI =====================

    /**
     * RSI de Wilder, entre 0 et 100. On separe les hausses et les baisses journalieres :
     * <ol>
     *   <li>au jour n : moyenne simple des n premieres hausses et des n premieres baisses ;</li>
     *   <li>ensuite, lissage de Wilder : moyenne = (moyenne precedente * (n - 1) + valeur du jour) / n ;</li>
     *   <li>RSI = 100 * hausse moyenne / (hausse moyenne + baisse moyenne).</li>
     * </ol>
     * Cette ecriture est equivalente a 100 - 100 / (1 + RS) avec RS = hausse moyenne / baisse moyenne,
     * sans division par zero : aucune baisse donne 100, aucune hausse donne 0, serie plate donne 50.
     */
    public static double[] rsi(double[] prices, int n) {
        checkPeriod(n);
        double[] out = nan(prices.length);
        if (prices.length <= n) {
            return out;                           // il faut n variations, donc n + 1 prix
        }
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= n; i++) {
            double change = prices[i] - prices[i - 1];
            if (change > 0) avgGain += change;
            else avgLoss -= change;
        }
        avgGain /= n;
        avgLoss /= n;
        out[n] = rsiValue(avgGain, avgLoss);
        for (int i = n + 1; i < prices.length; i++) {
            double change = prices[i] - prices[i - 1];
            avgGain = (avgGain * (n - 1) + Math.max(change, 0)) / n;
            avgLoss = (avgLoss * (n - 1) + Math.max(-change, 0)) / n;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        double total = avgGain + avgLoss;
        return total == 0 ? 50 : 100 * avgGain / total;
    }

    // ===================== MACD =====================

    /**
     * MACD (valeurs classiques : 12, 26, 9). La ligne macd commence au jour slow-1, la ligne de signal
     * et l'histogramme au jour slow + signal - 2.
     */
    public static Macd macd(double[] prices, int fast, int slow, int signal) {
        if (fast >= slow) {
            throw new IllegalArgumentException("MACD : la période rapide doit être plus courte que la période lente");
        }
        double[] fastEma = ema(prices, fast);
        double[] slowEma = ema(prices, slow);
        double[] line = nan(prices.length);
        for (int i = slow - 1; i < prices.length; i++) {
            line[i] = fastEma[i] - slowEma[i];
        }
        double[] signalLine = ema(line, signal, slow - 1);
        double[] histogram = nan(prices.length);
        for (int i = 0; i < prices.length; i++) {
            histogram[i] = line[i] - signalLine[i];   // NaN tant que le signal n'existe pas
        }
        return new Macd(line, signalLine, histogram);
    }

    // ===================== Bollinger =====================

    /**
     * Bandes de Bollinger (valeurs classiques : 20 jours, k = 2). L'ecart-type est celui des n derniers
     * prix, divise par n (ecart-type de population, convention de Bollinger).
     */
    public static Bands bollinger(double[] prices, int n, double k) {
        checkPeriod(n);
        if (!(k > 0)) {
            throw new IllegalArgumentException("Bollinger : le multiplicateur k doit être positif");
        }
        double[] middle = sma(prices, n);
        double[] upper = nan(prices.length);
        double[] lower = nan(prices.length);
        for (int i = n - 1; i < prices.length; i++) {
            double sum = 0;
            for (int j = i - n + 1; j <= i; j++) {
                sum += (prices[j] - middle[i]) * (prices[j] - middle[i]);
            }
            double sd = Math.sqrt(sum / n);
            upper[i] = middle[i] + k * sd;
            lower[i] = middle[i] - k * sd;
        }
        return new Bands(upper, middle, lower);
    }

    // ===================== Outils =====================

    private static void checkPeriod(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("La période d'un indicateur doit être au moins 1");
        }
    }

    private static double[] nan(int length) {
        double[] out = new double[length];
        Arrays.fill(out, Double.NaN);
        return out;
    }

    /** Moyenne de values[from..to], bornes incluses. */
    private static double mean(double[] values, int from, int to) {
        double sum = 0;
        for (int i = from; i <= to; i++) sum += values[i];
        return sum / (to - from + 1);
    }
}
