package tn.esprit.marketsim.coach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.coach.CoachDtos.CoachAnalysis;

/** Le coach reste utile sans cle API : il repond a partir de son analyse chiffree. */
class CoachOfflineTest {

    private static CoachAnalysis analysis() {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            dates.add(LocalDate.of(2023, 1, 1).plusDays(i));
            prices.add(100 * Math.exp(0.0004 * i + 0.02 * Math.sin(i / 8.0)));
        }
        return CoachAnalyzer.analyze(PriceSeries.of("AAPL", "TEST", dates, prices), "Apple Inc.", 0.5, null);
    }

    @Test
    void adviceQuestionsGetTheFullSpokenAnalysis() {
        CoachAnalysis a = analysis();
        for (String q : List.of("Que dois-je faire maintenant ?", "je dois acheter ?", "je vends ou je garde", "chtamel maintenant", "nechri wala nbi3 ?")) {
            assertEquals(a.speech(), CoachOffline.answer(q, a).orElseThrow(), q);
        }
    }

    @Test
    void chartRiskWhyAndHistoryQuestionsAreRoutedToTheRightAnswer() {
        CoachAnalysis a = analysis();
        assertTrue(CoachOffline.answer("analyse la courbe avec moi", a).orElseThrow().contains("tendance de fond"));
        assertTrue(CoachOffline.answer("c'est quoi le support et la résistance ?", a).orElseThrow().contains("support"));
        assertTrue(CoachOffline.answer("et mon risque ? où mettre le stop", a).orElseThrow().contains("stop"));
        assertTrue(CoachOffline.answer("pourquoi tu dis ça ?", a).orElseThrow().startsWith("Voici pourquoi"));
        assertTrue(CoachOffline.answer("est-ce que c'est fiable ? l'historique ?", a).orElseThrow().contains("pas assez d'historique"));
    }

    @Test
    void withoutAnAnalysisTheCoachAsksWhichAsset() {
        String reply = CoachOffline.answer("Que dois-je faire ?", null).orElseThrow();
        assertTrue(reply.contains("quel actif"));
    }

    @Test
    void unrelatedQuestionsAreLeftToTheGeneralAssistant() {
        Optional<String> reply = CoachOffline.answer("Raconte-moi une blague sur les banquiers", analysis());
        assertFalse(reply.isPresent());
    }

    @Test
    void accentsAndCaseDoNotMatter() {
        assertTrue(CoachOffline.answer("QUE FAIRE MAINTENANT", analysis()).isPresent());
        assertEquals("pourquoi resistance", CoachOffline.normalize("Pourquoi RÉSISTANCE"));
    }
}
