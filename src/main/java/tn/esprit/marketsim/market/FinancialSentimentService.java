package tn.esprit.marketsim.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;

/** Analyse FinBERT et agrégats Module 5. Les champs lexicaux historiques restent inchangés. */
@Service
@RequiredArgsConstructor
public class FinancialSentimentService {

    private static final int DEFAULT_PERIOD_DAYS = 30;
    private static final int MAX_PERIOD_DAYS = 365;
    private static final double LABEL_THRESHOLD = 0.15;

    private final MarketNewsRepository newsRepository;
    private final MarketNewsAnalysisRepository analysisRepository;
    private final FinancialNlpClient nlpClient;

    @Value("${app.market.finbert.model-name:ProsusAI/finbert}")
    private String modelName;

    @Value("${app.market.finbert.revision:b3e74f0635049fa34e60ecd12d49d1717d23821c}")
    private String modelVersion;

    public SentimentAnalysisResponse analyze(Long newsId) {
        MarketNews news = newsRepository.findById(newsId)
                .orElseThrow(() -> new NotFoundException("Actualité introuvable"));

        FinancialNlpClient.NlpResult result;
        if (!hasEnoughText(news.getHeadline())) {
            result = new FinancialNlpClient.NlpResult(MarketSentimentStatus.INSUFFICIENT_DATA,
                    null, null, null, null, null, null, null, Instant.now(), "HEADLINE_TOO_SHORT");
        } else {
            try {
                result = nlpClient.analyze(news.getHeadline());
                if (result == null) result = FinancialNlpClient.NlpResult.unavailable("EMPTY_NLP_RESPONSE");
            } catch (RuntimeException ex) {
                result = FinancialNlpClient.NlpResult.unavailable("NLP_SERVICE_ERROR");
            }
        }

        if (result.status() == MarketSentimentStatus.ANALYZED && !validAnalysis(result)) {
            result = FinancialNlpClient.NlpResult.unavailable("NLP_INVALID_RESPONSE");
        }

        MarketNewsAnalysis analysis = new MarketNewsAnalysis();
        analysis.setNewsId(news.getId());
        analysis.setHeadline(news.getHeadline());
        analysis.setSymbol(news.getSymbol());
        analysis.setPublishedAt(news.getPublishedAt());
        analysis.setStatus(result.status());
        analysis.setAnalyzedAt(result.analyzedAt() == null ? Instant.now() : result.analyzedAt());
        analysis.setErrorCode(result.errorCode());

        if (result.status() == MarketSentimentStatus.ANALYZED) {
            analysis.setSentimentLabel(result.sentimentLabel());
            analysis.setPositiveProbability(decimal(result.positiveProbability()));
            analysis.setNegativeProbability(decimal(result.negativeProbability()));
            analysis.setNeutralProbability(decimal(result.neutralProbability()));
            analysis.setSentimentScore(decimal(result.positiveProbability() - result.negativeProbability()));
            analysis.setModelName(modelName);
            analysis.setModelVersion(modelVersion);
            analysis.setErrorCode(null);
        }

        return SentimentAnalysisResponse.from(analysisRepository.save(analysis));
    }

    @Transactional(readOnly = true)
    public SentimentSummaryResponse summary(String symbol, Integer requestedDays) {
        String normalizedSymbol = requireSymbol(symbol);
        int days = normalizeDays(requestedDays);
        Instant end = Instant.now();
        Instant start = end.minusSeconds(days * 24L * 60L * 60L);
        List<MarketNewsAnalysis> rows = latestSuccessful(normalizedSymbol.toLowerCase(Locale.ROOT), start, end);
        return summarize(normalizedSymbol.toUpperCase(Locale.ROOT), days, start, end, rows);
    }

    @Transactional(readOnly = true)
    public SentimentHistoryResponse history(String symbol, Integer requestedDays) {
        SentimentSummaryResponse summary = summary(symbol, requestedDays);
        if (summary.analyzedArticleCount() == 0) {
            return new SentimentHistoryResponse(summary.symbol(), summary.periodDays(),
                    summary.periodStart(), summary.periodEnd(), MarketSentimentStatus.INSUFFICIENT_DATA, List.of());
        }

        List<MarketNewsAnalysis> rows = latestSuccessful(
                summary.symbol().toLowerCase(Locale.ROOT), summary.periodStart(), summary.periodEnd());
        Map<LocalDate, List<MarketNewsAnalysis>> grouped = new TreeMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(
                row.getPublishedAt().atZone(ZoneOffset.UTC).toLocalDate(), ignored -> new ArrayList<>()).add(row));

        List<SentimentHistoryResponse.DailyPoint> points = grouped.entrySet().stream()
                .map(entry -> dailyPoint(entry.getKey(), entry.getValue()))
                .toList();
        return new SentimentHistoryResponse(summary.symbol(), summary.periodDays(),
                summary.periodStart(), summary.periodEnd(), MarketSentimentStatus.ANALYZED, points);
    }

    private List<MarketNewsAnalysis> latestSuccessful(String symbol, Instant start, Instant end) {
        return analysisRepository.findLatestSuccessfulForPeriod(symbol, start, end,
                MarketSentimentStatus.ANALYZED, modelName, modelVersion);
    }

    private SentimentSummaryResponse summarize(String symbol, int days, Instant start, Instant end,
                                               List<MarketNewsAnalysis> rows) {
        if (rows.isEmpty()) {
            return new SentimentSummaryResponse(symbol, days, start, end, 0,
                    0, 0, 0, null, null, modelName, modelVersion,
                    MarketSentimentStatus.INSUFFICIENT_DATA, List.of());
        }

        long positive = rows.stream().filter(r -> r.getSentimentLabel() == SentimentAnalyzer.Label.POSITIVE).count();
        long negative = rows.stream().filter(r -> r.getSentimentLabel() == SentimentAnalyzer.Label.NEGATIVE).count();
        long neutral = rows.size() - positive - negative;
        double score = rows.stream().mapToDouble(r -> r.getSentimentScore().doubleValue()).average().orElse(0);
        String overall = score > LABEL_THRESHOLD ? "POSITIVE" : score < -LABEL_THRESHOLD ? "NEGATIVE" : "NEUTRAL";
        List<SentimentSummaryResponse.ArticleAnalysis> articles = rows.stream()
                .map(this::articleDto)
                .toList();
        double total = rows.size();
        return new SentimentSummaryResponse(symbol, days, start, end, rows.size(),
                percentage(positive, total), percentage(negative, total), percentage(neutral, total),
                overall, score, modelName, modelVersion, MarketSentimentStatus.ANALYZED, articles);
    }

    private SentimentSummaryResponse.ArticleAnalysis articleDto(MarketNewsAnalysis row) {
        return new SentimentSummaryResponse.ArticleAnalysis(
                row.getNewsId(), row.getHeadline(), row.getSentimentLabel().name(),
                row.getPositiveProbability().doubleValue(), row.getNegativeProbability().doubleValue(),
                row.getNeutralProbability().doubleValue(), row.getSentimentScore().doubleValue(),
                row.getModelName(), row.getModelVersion(), row.getAnalyzedAt());
    }

    private SentimentHistoryResponse.DailyPoint dailyPoint(LocalDate date, List<MarketNewsAnalysis> rows) {
        long positive = rows.stream().filter(r -> r.getSentimentLabel() == SentimentAnalyzer.Label.POSITIVE).count();
        long negative = rows.stream().filter(r -> r.getSentimentLabel() == SentimentAnalyzer.Label.NEGATIVE).count();
        long neutral = rows.size() - positive - negative;
        double total = rows.size();
        double score = rows.stream().mapToDouble(r -> r.getSentimentScore().doubleValue()).average().orElse(0);
        return new SentimentHistoryResponse.DailyPoint(date, rows.size(),
                percentage(positive, total), percentage(negative, total), percentage(neutral, total), score);
    }

    private static boolean validAnalysis(FinancialNlpClient.NlpResult result) {
        Double positive = result.positiveProbability();
        Double negative = result.negativeProbability();
        Double neutral = result.neutralProbability();
        if (result.sentimentLabel() == null || positive == null || negative == null || neutral == null
                || !Double.isFinite(positive) || !Double.isFinite(negative) || !Double.isFinite(neutral)
                || positive < 0 || positive > 1 || negative < 0 || negative > 1 || neutral < 0 || neutral > 1
                || Math.abs(positive + negative + neutral - 1) > 0.01
                || result.modelName() == null || result.modelVersion() == null
                || result.analyzedAt() == null) return false;
        double score = positive - negative;
        return Double.isFinite(score)
                && result.sentimentLabel() == labelFor(result.positiveProbability(), result.negativeProbability(),
                        result.neutralProbability());
    }

    private static SentimentAnalyzer.Label labelFor(double positive, double negative, double neutral) {
        if (positive >= negative && positive >= neutral) return SentimentAnalyzer.Label.POSITIVE;
        if (negative >= positive && negative >= neutral) return SentimentAnalyzer.Label.NEGATIVE;
        return SentimentAnalyzer.Label.NEUTRAL;
    }

    private static boolean hasEnoughText(String text) {
        if (text == null || text.trim().length() < 20) return false;
        return java.util.Arrays.stream(text.trim().split("\\s+"))
                .filter(token -> token.chars().anyMatch(Character::isLetterOrDigit))
                .count() >= 3;
    }

    private static double percentage(long count, double total) {
        return Math.round(count * 10000.0 / total) / 100.0;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP);
    }

    private static int normalizeDays(Integer days) {
        if (days == null) return DEFAULT_PERIOD_DAYS;
        if (days < 1 || days > MAX_PERIOD_DAYS) {
            throw new IllegalArgumentException("days doit être compris entre 1 et 365");
        }
        return days;
    }

    private static String requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol est obligatoire");
        String normalized = symbol.trim();
        if (normalized.length() > 64) throw new IllegalArgumentException("symbol est invalide");
        return normalized;
    }
}
