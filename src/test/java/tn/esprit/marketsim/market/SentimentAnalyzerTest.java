package tn.esprit.marketsim.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SentimentAnalyzerTest {

    private SentimentAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SentimentAnalyzer();
    }

    @Test
    void positiveHeadlinesAreDetectedInFrenchAndEnglish() {
        double scoreEn = analyzer.score("Apple beats earnings expectations, raises full-year guidance");
        assertTrue(scoreEn > 0, "English positive headline should have positive score: " + scoreEn);
        assertEquals(SentimentAnalyzer.Label.POSITIVE, analyzer.label(scoreEn));

        double scoreBtc = analyzer.score("Bitcoin ETF inflows hit record on institutional demand surge");
        assertTrue(scoreBtc > 0, "BTC positive headline should have positive score: " + scoreBtc);
        assertEquals(SentimentAnalyzer.Label.POSITIVE, analyzer.label(scoreBtc));

        double scoreFr = analyzer.score("Le CAC 40 rebondit fortement et signe un nouveau sommet");
        assertTrue(scoreFr > 0, "French positive headline should have positive score: " + scoreFr);
        assertEquals(SentimentAnalyzer.Label.POSITIVE, analyzer.label(scoreFr));
    }

    @Test
    void negativeHeadlinesAreDetectedInFrenchAndEnglish() {
        double scoreOil = analyzer.score("Oil inventories rise unexpectedly, prices under pressure");
        assertTrue(scoreOil < 0, "Negative pressure headline should have negative score: " + scoreOil);
        assertEquals(SentimentAnalyzer.Label.NEGATIVE, analyzer.label(scoreOil));

        double scoreFr = analyzer.score("Chute brutale à la Bourse de Paris : crise et krach redoutés");
        assertTrue(scoreFr < 0, "French negative headline should have negative score: " + scoreFr);
        assertEquals(SentimentAnalyzer.Label.NEGATIVE, analyzer.label(scoreFr));

        double scoreRecession = analyzer.score("Recession fears trigger deep market selloff and crash");
        assertTrue(scoreRecession < 0, "Recession fears should be negative: " + scoreRecession);
        assertEquals(SentimentAnalyzer.Label.NEGATIVE, analyzer.label(scoreRecession));
    }

    @Test
    void rateHikesAreTreatedWithContext() {
        // Une hausse des taux n'est pas un signal d'optimisme pour les actions
        double scoreHikeFr = analyzer.score("La Fed procède à une nouvelle hausse des taux directeurs");
        assertTrue(scoreHikeFr <= 0, "Rate hike should not be positive: " + scoreHikeFr);

        double scoreHikeEn = analyzer.score("Federal Reserve signals another aggressive rate hike");
        assertTrue(scoreHikeEn < 0, "Aggressive rate hike is negative for market: " + scoreHikeEn);
        assertEquals(SentimentAnalyzer.Label.NEGATIVE, analyzer.label(scoreHikeEn));
    }

    @Test
    void neutralOrBlankHeadlinesReturnZeroAndNeutralLabel() {
        assertEquals(0.0, analyzer.score(null));
        assertEquals(SentimentAnalyzer.Label.NEUTRAL, analyzer.label(0.0));

        assertEquals(0.0, analyzer.score("   "));
        assertEquals(SentimentAnalyzer.Label.NEUTRAL, analyzer.label(analyzer.score("   ")));

        double scoreNeutral = analyzer.score("La BCE publie son rapport trimestriel sur les flux de capitaux");
        assertEquals(SentimentAnalyzer.Label.NEUTRAL, analyzer.label(scoreNeutral));
    }
}
