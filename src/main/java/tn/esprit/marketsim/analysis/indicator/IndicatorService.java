package tn.esprit.marketsim.analysis.indicator;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.indicator.IndicatorDtos.BollingerSeries;
import tn.esprit.marketsim.analysis.indicator.IndicatorDtos.IndicatorResponse;
import tn.esprit.marketsim.analysis.indicator.IndicatorDtos.MacdSeries;
import tn.esprit.marketsim.analysis.indicator.IndicatorDtos.RsiSeries;

/**
 * Module 3 : calcule les indicateurs techniques d'un actif a partir de son historique reel
 * (fourni par le module 6, {@link MarketDataService}).
 *
 * <p>Les parametres arrivent en texte ({@code "20,50"}) pour pouvoir renvoyer des messages d'erreur
 * clairs. Une {@link IllegalArgumentException} devient une reponse HTTP 400 (GlobalExceptionHandler).
 */
@Service
@RequiredArgsConstructor
public class IndicatorService {

    // Valeurs utilisees quand l'appel ne demande aucun indicateur
    static final String DEFAULT_SMA = "20,50";
    static final String DEFAULT_EMA = "20";
    static final String DEFAULT_RSI = "14";
    static final String DEFAULT_MACD = "12,26,9";
    static final String DEFAULT_BOLLINGER = "20,2";

    /** Nombre maximum de periodes pour SMA et EMA dans un meme appel. */
    static final int MAX_PERIODS = 5;

    private final MarketDataService marketData;

    public IndicatorResponse indicators(String symbol, String sma, String ema, String rsi, String macd,
                                        String bollinger, LocalDate from, LocalDate to) {
        return compute(marketData.series(symbol), sma, ema, rsi, macd, bollinger, from, to);
    }

    /**
     * Calcul sur une serie deja chargee (methode statique : testable sans base ni reseau).
     * Les indicateurs sont calcules sur TOUT l'historique, puis on garde la periode [from, to] :
     * ainsi le debut de la fenetre demandee n'a pas de trou de chauffe.
     */
    static IndicatorResponse compute(PriceSeries series, String sma, String ema, String rsi, String macd,
                                     String bollinger, LocalDate from, LocalDate to) {
        boolean none = blank(sma) && blank(ema) && blank(rsi) && blank(macd) && blank(bollinger);
        if (none) {
            sma = DEFAULT_SMA;
            ema = DEFAULT_EMA;
            rsi = DEFAULT_RSI;
            macd = DEFAULT_MACD;
            bollinger = DEFAULT_BOLLINGER;
        }

        double[] closes = series.closes();
        int size = closes.length;

        // Fenetre [lo, hi] a renvoyer
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("La date de début doit précéder la date de fin");
        }
        int lo = 0;
        while (lo < size && from != null && series.dates()[lo].isBefore(from)) lo++;
        int hi = size - 1;
        while (hi >= 0 && to != null && series.dates()[hi].isAfter(to)) hi--;
        if (lo > hi) {
            throw new IllegalArgumentException("Aucune donnée pour " + series.symbol() + " sur la période demandée");
        }

        Map<Integer, Double[]> smaOut = null;
        if (!blank(sma)) {
            smaOut = new LinkedHashMap<>();
            for (int n : periods("sma", sma, size)) {
                smaOut.put(n, window(Indicators.sma(closes, n), lo, hi));
            }
        }

        Map<Integer, Double[]> emaOut = null;
        if (!blank(ema)) {
            emaOut = new LinkedHashMap<>();
            for (int n : periods("ema", ema, size)) {
                emaOut.put(n, window(Indicators.ema(closes, n), lo, hi));
            }
        }

        RsiSeries rsiOut = null;
        if (!blank(rsi)) {
            double[] v = numbers("rsi", rsi);
            if (v.length != 1) {
                throw new IllegalArgumentException("rsi : une seule période attendue, ex. rsi=14");
            }
            int n = period("rsi", v[0], size);
            rsiOut = new RsiSeries(n, window(Indicators.rsi(closes, n), lo, hi));
        }

        MacdSeries macdOut = null;
        if (!blank(macd)) {
            double[] v = numbers("macd", macd);
            if (v.length != 3) {
                throw new IllegalArgumentException("macd : trois périodes attendues (rapide, lente, signal), ex. macd=12,26,9");
            }
            int fast = period("macd", v[0], size);
            int slow = period("macd", v[1], size);
            int signal = period("macd", v[2], size);
            if (fast >= slow) {
                throw new IllegalArgumentException("macd : la période rapide doit être plus courte que la période lente");
            }
            if (slow + signal > size) {
                throw new IllegalArgumentException("macd : historique trop court (" + size + " jours) pour ces périodes");
            }
            Indicators.Macd m = Indicators.macd(closes, fast, slow, signal);
            macdOut = new MacdSeries(fast, slow, signal,
                    window(m.macd(), lo, hi), window(m.signal(), lo, hi), window(m.histogram(), lo, hi));
        }

        BollingerSeries bollingerOut = null;
        if (!blank(bollinger)) {
            double[] v = numbers("bollinger", bollinger);
            if (v.length != 2) {
                throw new IllegalArgumentException("bollinger : période et multiplicateur attendus, ex. bollinger=20,2");
            }
            int n = period("bollinger", v[0], size);
            double k = v[1];
            if (k <= 0 || k > 10) {
                throw new IllegalArgumentException("bollinger : le multiplicateur doit être entre 0 et 10");
            }
            Indicators.Bands b = Indicators.bollinger(closes, n, k);
            bollingerOut = new BollingerSeries(n, k,
                    window(b.upper(), lo, hi), window(b.middle(), lo, hi), window(b.lower(), lo, hi));
        }

        return new IndicatorResponse(series.symbol(), series.source(),
                Arrays.copyOfRange(series.dates(), lo, hi + 1),
                Arrays.stream(closes, lo, hi + 1).map(IndicatorService::round6).toArray(),
                smaOut, emaOut, rsiOut, macdOut, bollingerOut);
    }

    // ===================== Lecture des parametres =====================

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** "20,50" -> [20, 50]. */
    private static double[] numbers(String name, String raw) {
        String[] parts = raw.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " : « " + parts[i].trim() + " » n'est pas un nombre");
            }
        }
        return out;
    }

    /** Liste de periodes pour SMA / EMA, sans doublon. */
    private static int[] periods(String name, String raw, int size) {
        int[] out = Arrays.stream(numbers(name, raw)).mapToInt(v -> period(name, v, size)).distinct().toArray();
        if (out.length > MAX_PERIODS) {
            throw new IllegalArgumentException(name + " : " + MAX_PERIODS + " périodes au maximum");
        }
        return out;
    }

    /** Une periode valide est un entier entre 2 et la taille de l'historique (exclue). */
    private static int period(String name, double value, int size) {
        if (value != Math.rint(value) || value < 2) {
            throw new IllegalArgumentException(name + " : la période doit être un entier supérieur ou égal à 2");
        }
        if (value >= size) {
            throw new IllegalArgumentException(name + " : période trop longue pour l'historique disponible ("
                    + size + " jours)");
        }
        return (int) value;
    }

    // ===================== Mise en forme =====================

    /** Garde les cases lo..hi, remplace NaN par null (NaN n'existe pas en JSON) et arrondit a 6 decimales. */
    private static Double[] window(double[] values, int lo, int hi) {
        Double[] out = new Double[hi - lo + 1];
        for (int i = lo; i <= hi; i++) {
            out[i - lo] = Double.isNaN(values[i]) ? null : round6(values[i]);
        }
        return out;
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
