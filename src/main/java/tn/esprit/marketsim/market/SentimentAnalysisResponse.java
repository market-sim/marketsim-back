package tn.esprit.marketsim.market;

import java.math.BigDecimal;
import java.time.Instant;

/** Réponse détaillée d'une tentative d'analyse article. */
public record SentimentAnalysisResponse(
        Long newsId,
        String sentimentLabel,
        BigDecimal positiveProbability,
        BigDecimal negativeProbability,
        BigDecimal neutralProbability,
        BigDecimal sentimentScore,
        String modelName,
        String modelVersion,
        Instant analyzedAt,
        MarketSentimentStatus status,
        String errorCode) {

    static SentimentAnalysisResponse from(MarketNewsAnalysis analysis) {
        return new SentimentAnalysisResponse(
                analysis.getNewsId(),
                analysis.getSentimentLabel() == null ? null : analysis.getSentimentLabel().name(),
                analysis.getPositiveProbability(),
                analysis.getNegativeProbability(),
                analysis.getNeutralProbability(),
                analysis.getSentimentScore(),
                analysis.getModelName(),
                analysis.getModelVersion(),
                analysis.getAnalyzedAt(),
                analysis.getStatus(),
                analysis.getErrorCode());
    }
}
