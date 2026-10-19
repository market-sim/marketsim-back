package tn.esprit.marketsim.coach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.coach.CoachDtos.CoachAnalysis;
import tn.esprit.marketsim.coach.CoachDtos.Rule;

/** Le coach ne parle que de chiffres calcules : on verifie leur coherence sur des courbes synthetiques. */
class CoachAnalyzerTest {

    private static PriceSeries series(int n, IntToDoubleFunction price) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        LocalDate d = LocalDate.of(2022, 1, 1);
        for (int i = 0; i < n; i++) {
            dates.add(d.plusDays(i));
            prices.add(price.applyAsDouble(i));
        }
        return PriceSeries.of("TEST", "TEST", dates, prices);
    }

    /** Tendance haussiere reguliere avec des oscillations. */
    private static PriceSeries uptrend() {
        return series(500, i -> 100 * Math.exp(0.0015 * i + 0.01 * Math.sin(i / 9.0)));
    }

    private static PriceSeries downtrend() {
        return series(500, i -> 200 * Math.exp(-0.0007 * i + 0.012 * Math.sin(i / 7.0)));
    }

    private static void assertCoherent(CoachAnalysis a) {
        assertTrue(a.levels().support() < a.price(), "support sous le cours");
        assertTrue(a.levels().stop() < a.price(), "stop sous le cours");
        assertTrue(a.levels().target() > a.price(), "objectif au-dessus du cours");
        if (a.levels().resistance() != null) assertTrue(a.levels().resistance() > a.price(), "resistance au-dessus du cours");
        // objectif = 2 fois le risque (meme regle que le backtester)
        assertEquals(2 * (a.price() - a.levels().stop()), a.levels().target() - a.price(), a.price() * 1e-9);
        assertEquals(3, a.plan().size());
        assertEquals(1, a.plan().stream().filter(Rule::active).count(), "une seule regle active");
        assertEquals(a.action(), a.plan().stream().filter(Rule::active).findFirst().orElseThrow().action(),
                "la regle active correspond au signal");
        assertEquals(CoachAnalyzer.CHART_BARS, a.chart().size());
        assertTrue(a.confidence() >= 0.5 && a.confidence() <= 1.0, "confiance " + a.confidence());
        assertTrue(a.indicators().rsi14() >= 0 && a.indicators().rsi14() <= 100, "RSI " + a.indicators().rsi14());
        assertTrue(a.speech().contains("pas un conseil d'investissement"), "rappel obligatoire");
        assertTrue(a.speech().contains(a.name()), "le coach nomme l'actif");
        assertNotNull(a.disclaimer());
    }

    @Test
    void anUptrendIsReadAsBullishWithCoherentLevels() {
        CoachAnalysis a = CoachAnalyzer.analyze(uptrend(), "Actif test", 0.5, null);
        assertCoherent(a);
        assertTrue(a.indicators().sma50() > a.indicators().sma200(), "moyenne 50 jours au-dessus de la 200 jours");
        assertEquals("HAUSSIERE", a.indicators().trend());
    }

    @Test
    void aDowntrendNeverProducesABuy() {
        CoachAnalysis a = CoachAnalyzer.analyze(downtrend(), "Actif test", 0.5, null);
        assertCoherent(a);
        assertEquals("BAISSIERE", a.indicators().trend());
        assertNotEquals(AgentAction.BUY, a.action());
    }

    @Test
    void theTrendFilterBlocksABuyAgainstTheLongTermTrend() {
        // longue baisse puis petit rebond : cours sous la MM50, elle-meme sous la MM200, mais momentum 20 j positif
        PriceSeries s = series(400, i -> {
            if (i < 350) return 300 * Math.exp(-0.0016 * i);                                // longue baisse lente : 300 -> ~171
            if (i < 375) return 171 * Math.exp(Math.log(100 / 171.0) * (i - 350) / 25.0);   // chute rapide jusqu'a 100
            if (i < 380) return 100;                                                        // plancher
            return 100 * Math.exp(0.004 * (i - 380));                                       // rebond de ~8 %
        });
        CoachAnalysis a = CoachAnalyzer.analyze(s, "Actif test", 0.5, null);
        assertCoherent(a);
        assertEquals("BAISSIERE", a.indicators().trend(), "le scenario doit bien etre baissier");
        assertNotEquals(AgentAction.BUY, a.action(), "pas d'achat contre la tendance de fond");
        if (a.reasons().get(0).label().equals("Filtre de tendance")) {
            assertEquals(AgentAction.HOLD, a.action());
            assertEquals(0.0, a.reasons().get(0).contribution(), 0.0);
        }
    }

    @Test
    void sameInputGivesTheSameAnswer() {
        CoachAnalysis a = CoachAnalyzer.analyze(uptrend(), "Actif test", 0.5, null);
        CoachAnalysis b = CoachAnalyzer.analyze(uptrend(), "Actif test", 0.5, null);
        assertEquals(a.action(), b.action());
        assertEquals(a.speech(), b.speech());
        assertEquals(a.levels(), b.levels());
        assertEquals(a.dataHash(), b.dataHash());
    }

    @Test
    void aMoreCautiousProfileUsesAWiderStop() {
        PriceSeries s = uptrend();
        double cautious = 1 - CoachAnalyzer.analyze(s, "A", 0.1, null).levels().stop() / s.closes()[s.size() - 1];
        double aggressive = 1 - CoachAnalyzer.analyze(s, "A", 0.9, null).levels().stop() / s.closes()[s.size() - 1];
        assertTrue(cautious > aggressive, "prudent = stop plus large que l'agressif");
    }

    @Test
    void movingAveragesAndExtremaMatchHandComputedValues() {
        double[] p = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(8.5, CoachAnalyzer.sma(p, 9, 4), 1e-12);      // (7+8+9+10)/4
        assertEquals(7, CoachAnalyzer.min(p, 9, 4), 1e-12);
        assertEquals(10, CoachAnalyzer.max(p, 9, 4), 1e-12);
        assertEquals("1,1790", CoachAnalyzer.px(1.179));
        assertEquals("192,45", CoachAnalyzer.px(192.45));
        assertTrue(CoachAnalyzer.px(82676.4).replace(" ", "").replace(" ", "").startsWith("82676"));
    }

    @Test
    void tooShortHistoryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CoachAnalyzer.analyze(series(150, i -> 100 + i), "A", 0.5, null));
    }

    @Test
    void theTrackRecordIsComputedAndQuotedInTheSpeech() {
        PriceSeries s = series(1400, i -> 100 * Math.exp(0.0004 * i + 0.03 * Math.sin(i / 11.0)));
        var tr = CoachTrackRecord.compute(s, 0.5);
        assertNotNull(tr);
        assertTrue(tr.windows() >= 3);
        assertTrue(tr.text().contains("buy and hold"));
        CoachAnalysis a = CoachAnalyzer.analyze(s, "Actif test", 0.5, tr);
        assertTrue(a.speech().contains("historique réel hors échantillon"), "le coach annonce le bilan reel de sa strategie");
        assertTrue(a.speech().length() < 1100, "discours court : " + a.speech().length());
        assertTrue(CoachService.context("Trading & Risk", a).contains("CONSEILLÉ MAINTENANT"));
    }

    @Test
    void noTrackRecordWhenTheHistoryIsTooShort() {
        assertEquals(null, CoachTrackRecord.compute(series(600, i -> 100 + i * 0.1), 0.5));
    }
}
