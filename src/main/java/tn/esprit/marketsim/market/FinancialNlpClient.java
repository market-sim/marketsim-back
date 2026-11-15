package tn.esprit.marketsim.market;

import java.time.Instant;

/** Contrat interne Spring → service FinBERT. */
public interface FinancialNlpClient {

    NlpResult analyze(String text);

    record NlpResult(
            MarketSentimentStatus status,
            SentimentAnalyzer.Label sentimentLabel,
            Double positiveProbability,
            Double negativeProbability,
            Double neutralProbability,
            Double sentimentScore,
            String modelName,
            String modelVersion,
            Instant analyzedAt,
            String errorCode) {

        public static NlpResult unavailable(String errorCode) {
            return new NlpResult(MarketSentimentStatus.UNAVAILABLE, null, null, null, null, null,
                    null, null, Instant.now(), errorCode);
        }
    }
}
