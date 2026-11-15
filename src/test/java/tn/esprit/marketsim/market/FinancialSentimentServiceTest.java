package tn.esprit.marketsim.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import tn.esprit.marketsim.common.NotFoundException;

@ExtendWith(MockitoExtension.class)
class FinancialSentimentServiceTest {

    @Mock private MarketNewsRepository newsRepository;
    @Mock private MarketNewsAnalysisRepository analysisRepository;
    @Mock private FinancialNlpClient nlpClient;
    @InjectMocks private FinancialSentimentService service;

    private static final String MODEL = "ProsusAI/finbert";
    private static final String VERSION = "b3e74f0635049fa34e60ecd12d49d1717d23821c";

    @BeforeEach
    void configureModelIdentity() {
        ReflectionTestUtils.setField(service, "modelName", MODEL);
        ReflectionTestUtils.setField(service, "modelVersion", VERSION);
    }

    @Test
    void invalidNewsIdReturnsNotFound() {
        when(newsRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.analyze(999L));
        verify(nlpClient, never()).analyze(any());
    }

    @Test
    void shortHeadlineReturnsInsufficientDataWithoutCallingModel() {
        MarketNews news = news(1L, "Stocks rise");
        when(newsRepository.findById(1L)).thenReturn(Optional.of(news));
        when(analysisRepository.save(any(MarketNewsAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));

        SentimentAnalysisResponse response = service.analyze(1L);

        assertEquals(MarketSentimentStatus.INSUFFICIENT_DATA, response.status());
        assertNull(response.sentimentLabel());
        verify(nlpClient, never()).analyze(any());
    }

    @Test
    void unavailableModelReturnsUnavailableAndNeverUsesLexicalSentiment() {
        MarketNews news = news(2L, "The company reports strong revenue growth across international markets.");
        when(newsRepository.findById(2L)).thenReturn(Optional.of(news));
        when(nlpClient.analyze(news.getHeadline())).thenReturn(FinancialNlpClient.NlpResult.unavailable("NLP_SERVICE_UNREACHABLE"));
        when(analysisRepository.save(any(MarketNewsAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));

        SentimentAnalysisResponse response = service.analyze(2L);

        assertEquals(MarketSentimentStatus.UNAVAILABLE, response.status());
        assertNull(response.sentimentLabel());
        assertNull(response.sentimentScore());
        assertEquals("NLP_SERVICE_UNREACHABLE", response.errorCode());
    }

    @Test
    void validModelResultPersistsProbabilitiesAndMetadata() {
        MarketNews news = news(3L, "The company beats earnings expectations and raises its annual guidance.");
        when(newsRepository.findById(3L)).thenReturn(Optional.of(news));
        when(nlpClient.analyze(news.getHeadline())).thenReturn(new FinancialNlpClient.NlpResult(
                MarketSentimentStatus.ANALYZED, SentimentAnalyzer.Label.POSITIVE,
                .82, .05, .13, .77, MODEL, VERSION, Instant.now(), null));
        when(analysisRepository.save(any(MarketNewsAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));

        SentimentAnalysisResponse response = service.analyze(3L);

        assertEquals(MarketSentimentStatus.ANALYZED, response.status());
        assertEquals(SentimentAnalyzer.Label.POSITIVE.name(), response.sentimentLabel());
        assertEquals(new BigDecimal("0.8200000"), response.positiveProbability());
        assertEquals(new BigDecimal("0.7700000"), response.sentimentScore());
        assertEquals(MODEL, response.modelName());
        assertEquals(VERSION, response.modelVersion());
    }

    @Test
    void aggregationUsesSelectedInstrumentPeriodAndLatestRowsOnly() {
        List<MarketNewsAnalysis> rows = List.of(
                analysis(11L, "AAPL", SentimentAnalyzer.Label.POSITIVE, .8, .1, .1, .7),
                analysis(12L, "AAPL", SentimentAnalyzer.Label.NEGATIVE, .1, .8, .1, -.7),
                analysis(13L, "AAPL", SentimentAnalyzer.Label.NEUTRAL, .2, .2, .6, 0));
        when(analysisRepository.findLatestSuccessfulForPeriod(eq("aapl"), any(), any(),
                eq(MarketSentimentStatus.ANALYZED), eq(MODEL), eq(VERSION))).thenReturn(rows);

        SentimentSummaryResponse response = service.summary("aapl", 7);

        assertEquals(3, response.analyzedArticleCount());
        assertEquals(33.33, response.positivePercentage());
        assertEquals(33.33, response.negativePercentage());
        assertEquals(33.33, response.neutralPercentage());
        assertEquals("NEUTRAL", response.overallSentiment());
        assertEquals(0.0, response.sentimentScore(), 1e-9);
        assertEquals("AAPL", response.symbol());
        assertTrue(ChronoUnit.DAYS.between(response.periodStart(), response.periodEnd()) >= 6);
        verify(analysisRepository).findLatestSuccessfulForPeriod(eq("aapl"), any(), any(),
                eq(MarketSentimentStatus.ANALYZED), eq(MODEL), eq(VERSION));
    }

    @Test
    void historyGroupsByPublishedUtcDay() {
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        MarketNewsAnalysis first = analysis(21L, "TSLA", SentimentAnalyzer.Label.POSITIVE, .8, .1, .1, .7);
        first.setPublishedAt(yesterday);
        MarketNewsAnalysis second = analysis(22L, "TSLA", SentimentAnalyzer.Label.NEGATIVE, .1, .8, .1, -.7);
        second.setPublishedAt(yesterday.plusSeconds(300));
        when(analysisRepository.findLatestSuccessfulForPeriod(eq("tsla"), any(), any(),
                eq(MarketSentimentStatus.ANALYZED), eq(MODEL), eq(VERSION))).thenReturn(List.of(first, second));

        SentimentHistoryResponse response = service.history("TSLA", 30);

        assertEquals(MarketSentimentStatus.ANALYZED, response.status());
        assertEquals(1, response.points().size());
        assertEquals(2, response.points().get(0).analyzedArticleCount());
        assertEquals(50.0, response.points().get(0).positivePercentage());
        assertEquals(50.0, response.points().get(0).negativePercentage());
    }

    private static MarketNews news(Long id, String headline) {
        MarketNews news = MarketNews.of(headline, "Reuters", "https://example.test/article/" + id,
                "AAPL", Instant.now().minus(1, ChronoUnit.HOURS), 0, SentimentAnalyzer.Label.NEUTRAL);
        news.setId(id);
        return news;
    }

    private static MarketNewsAnalysis analysis(long newsId, String symbol, SentimentAnalyzer.Label label,
                                               double positive, double negative, double neutral, double score) {
        MarketNewsAnalysis analysis = new MarketNewsAnalysis();
        analysis.setNewsId(newsId);
        analysis.setHeadline("A sufficiently descriptive financial headline for " + symbol + " markets.");
        analysis.setSymbol(symbol);
        analysis.setPublishedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        analysis.setAnalyzedAt(Instant.now());
        analysis.setStatus(MarketSentimentStatus.ANALYZED);
        analysis.setSentimentLabel(label);
        analysis.setPositiveProbability(BigDecimal.valueOf(positive));
        analysis.setNegativeProbability(BigDecimal.valueOf(negative));
        analysis.setNeutralProbability(BigDecimal.valueOf(neutral));
        analysis.setSentimentScore(BigDecimal.valueOf(score));
        analysis.setModelName(MODEL);
        analysis.setModelVersion(VERSION);
        return analysis;
    }
}
