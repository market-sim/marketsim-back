package tn.esprit.marketsim.analysis.indicator;

import java.time.LocalDate;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Module 3 : reponse de {@code GET /api/analysis/indicators}. Format "en colonnes" : chaque tableau a la
 * meme taille que {@code dates}, la case i correspond au jour {@code dates[i]}. Une valeur {@code null}
 * signifie "pas encore assez d'historique" (periode de chauffe de l'indicateur).
 */
public final class IndicatorDtos {

    private IndicatorDtos() {
    }

    /**
     * Un indicateur non demande est absent du JSON.
     *
     * @param sma une serie par periode demandee (cle = periode)
     * @param ema une serie par periode demandee (cle = periode)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndicatorResponse(String symbol, String source, LocalDate[] dates, double[] closes,
                                    Map<Integer, Double[]> sma, Map<Integer, Double[]> ema,
                                    RsiSeries rsi, MacdSeries macd, BollingerSeries bollinger) {
    }

    public record RsiSeries(int period, Double[] values) {
    }

    public record MacdSeries(int fast, int slow, int signal, Double[] macd, Double[] signalLine, Double[] histogram) {
    }

    public record BollingerSeries(int period, double k, Double[] upper, Double[] middle, Double[] lower) {
    }
}
