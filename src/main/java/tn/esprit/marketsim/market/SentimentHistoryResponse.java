package tn.esprit.marketsim.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Série quotidienne pour le graphique de sentiment. */
public record SentimentHistoryResponse(
        String symbol,
        int periodDays,
        Instant periodStart,
        Instant periodEnd,
        MarketSentimentStatus status,
        List<DailyPoint> points) {

    public record DailyPoint(
            LocalDate date,
            long analyzedArticleCount,
            double positivePercentage,
            double negativePercentage,
            double neutralPercentage,
            double sentimentScore) {}
}
