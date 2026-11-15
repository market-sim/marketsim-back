package tn.esprit.marketsim.market;

import java.time.Instant;
import java.util.List;

/** Agrégat informatif basé uniquement sur des résultats FinBERT réussis. */
public record SentimentSummaryResponse(
        String symbol,
        int periodDays,
        Instant periodStart,
        Instant periodEnd,
        long analyzedArticleCount,
        double positivePercentage,
        double negativePercentage,
        double neutralPercentage,
        String overallSentiment,
        Double sentimentScore,
        String modelName,
        String modelVersion,
        MarketSentimentStatus status,
        List<ArticleAnalysis> articles) {

    public record ArticleAnalysis(
            Long newsId,
            String headline,
            String sentimentLabel,
            double positiveProbability,
            double negativeProbability,
            double neutralProbability,
            double sentimentScore,
            String modelName,
            String modelVersion,
            Instant analyzedAt) {}
}
