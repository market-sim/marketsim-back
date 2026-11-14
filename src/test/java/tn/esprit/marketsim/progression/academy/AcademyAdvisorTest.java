package tn.esprit.marketsim.progression.academy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.progression.academy.AcademyAdvisor.Advice;
import tn.esprit.marketsim.progression.academy.AcademyAdvisor.Priority;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.GameHistoryItem;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.ProgressionResponse;
import tn.esprit.marketsim.user.UserLevel;

class AcademyAdvisorTest {

    private static ProgressionResponse stats(int games, int trades, double winRate, double pf, double discipline,
                                             double dd, int restarts, boolean bankrupt) {
        return new ProgressionResponse(1000, 1000, 1000, UserLevel.DEBUTANT, null, 40, games, trades, winRate, pf,
                discipline, dd, restarts, bankrupt, UserLevel.INTERMEDIAIRE, List.of(), null, Instant.now());
    }

    private static GameHistoryItem game(String symbol, double player, Double agent) {
        return new GameHistoryItem(1L, symbol, 63, 1000, 1000, 4, 2, 0.1, UserLevel.DEBUTANT, UserLevel.DEBUTANT,
                Instant.now(), player, agent, 0.0, true);
    }

    @Test
    void noGameRecommendsTheBasics() {
        Advice a = AcademyAdvisor.advise(null, List.of());
        assertEquals("market", a.recommendations().get(0).stationId());
        assertEquals(0, a.games());
        assertFalse(a.secretUnlocked());
    }

    @Test
    void undisciplinedTraderMustVisitTheFortressFirst() {
        Advice a = AcademyAdvisor.advise(stats(4, 20, 0.6, 1.4, 0.3, 0.05, 0, false), List.of());
        assertEquals("risk", a.recommendations().get(0).stationId());
        assertEquals(Priority.HIGH, a.recommendations().get(0).priority());
        assertTrue(a.recommendations().get(0).reasons().get(0).contains("30 %"));
    }

    @Test
    void reasonsForTheSameStationAreMerged() {
        Advice a = AcademyAdvisor.advise(stats(4, 20, 0.6, 1.4, 0.3, 0.05, 2, false), List.of());
        long risk = a.recommendations().stream().filter(r -> r.stationId().equals("risk")).count();
        assertEquals(1, risk);
        assertEquals(2, a.recommendations().get(0).reasons().size());
    }

    @Test
    void highPriorityComesBeforeMediumAndLow() {
        Advice a = AcademyAdvisor.advise(stats(3, 40, 0.3, 0.7, 0.9, 0.25, 0, false),
                List.of(game("AAPL", -0.1, 0.05), game("AAPL", -0.05, 0.02), game("AAPL", -0.02, 0.01)));
        assertEquals("crash", a.recommendations().get(0).stationId());
        List<String> ids = a.recommendations().stream().map(AcademyAdvisor.Recommendation::stationId).toList();
        assertTrue(ids.containsAll(List.of("psychology", "candles", "orders", "diversify", "indicators")));
        for (int i = 1; i < a.recommendations().size(); i++) {
            assertTrue(a.recommendations().get(i - 1).priority().ordinal() <= a.recommendations().get(i).priority().ordinal());
        }
    }

    @Test
    void tooFewTradesDoNotProduceStatisticalAdvice() {
        Advice a = AcademyAdvisor.advise(stats(1, 2, 0.0, 0.0, 0.0, 0.02, 0, false), List.of());
        assertTrue(a.recommendations().isEmpty());
    }

    @Test
    void beatingTheAgentThreeTimesUnlocksTheSecretRoom() {
        List<GameHistoryItem> games = List.of(game("AAPL", 0.10, 0.05), game("MSFT", 0.08, 0.02),
                game("TSLA", 0.03, 0.01), game("NVDA", -0.02, 0.04), game("AMZN", 0.01, null));
        Advice a = AcademyAdvisor.advise(stats(5, 20, 0.6, 1.5, 0.9, 0.05, 0, false), games);
        assertEquals(3, a.beatAgent());
        assertEquals(4, a.gamesVsAgent());
        assertTrue(a.secretUnlocked());
    }

    @Test
    void offlineMentorAnswersRevisionQuestionsWithRealNumbers() {
        Advice a = AcademyAdvisor.advise(stats(4, 20, 0.6, 1.4, 0.3, 0.05, 0, false), List.of());
        String reply = AcademyController.offline("qu'est-ce que je dois réviser ?", a).orElseThrow();
        assertTrue(reply.contains("Forteresse du Risque"));
        assertTrue(reply.contains("30 %"));
        assertTrue(AcademyController.offline("c'est quoi le RSI ?", a).isEmpty());
    }
}
