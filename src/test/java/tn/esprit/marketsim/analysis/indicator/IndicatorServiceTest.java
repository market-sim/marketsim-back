package tn.esprit.marketsim.analysis.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.indicator.IndicatorDtos.IndicatorResponse;

/** Lecture des parametres, valeurs par defaut et decoupage de la periode (sans base ni reseau). */
class IndicatorServiceTest {

    private static final LocalDate START = LocalDate.of(2023, 1, 1);

    /** 300 jours consecutifs a partir du 1er janvier 2023. */
    private static PriceSeries series() {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            dates.add(START.plusDays(i));
            prices.add(100 + 0.2 * i + 5 * Math.sin(i / 4.0));
        }
        return PriceSeries.of("TEST", "TEST", dates, prices);
    }

    private static IndicatorResponse compute(String sma, String ema, String rsi, String macd, String bollinger,
                                             LocalDate from, LocalDate to) {
        return IndicatorService.compute(series(), sma, ema, rsi, macd, bollinger, from, to);
    }

    private static void assertRejected(String sma, String ema, String rsi, String macd, String bollinger) {
        assertThrows(IllegalArgumentException.class, () -> compute(sma, ema, rsi, macd, bollinger, null, null));
    }

    @Test
    void withoutParametersAllDefaultIndicatorsAreReturned() {
        IndicatorResponse r = compute(null, null, null, null, null, null, null);
        assertEquals("TEST", r.symbol());
        assertEquals(300, r.dates().length);
        assertEquals(300, r.closes().length);
        assertEquals(Set.of(20, 50), r.sma().keySet());
        assertEquals(Set.of(20), r.ema().keySet());
        assertEquals(14, r.rsi().period());
        assertEquals(12, r.macd().fast());
        assertEquals(26, r.macd().slow());
        assertEquals(9, r.macd().signal());
        assertEquals(20, r.bollinger().period());
        assertEquals(2.0, r.bollinger().k());
        assertEquals(300, r.macd().histogram().length, "meme taille que les dates");
    }

    @Test
    void warmupDaysAreNull() {
        IndicatorResponse r = compute("20", null, "14", null, null, null, null);
        assertNull(r.sma().get(20)[18]);
        assertNotNull(r.sma().get(20)[19]);
        assertNull(r.rsi().values()[13]);
        assertNotNull(r.rsi().values()[14]);
    }

    @Test
    void onlyRequestedIndicatorsAreComputed() {
        IndicatorResponse r = compute("10, 30", null, null, null, null, null, null);
        assertEquals(Set.of(10, 30), r.sma().keySet());
        assertNull(r.ema());
        assertNull(r.rsi());
        assertNull(r.macd());
        assertNull(r.bollinger());
    }

    @Test
    void windowIsCutAfterComputingOnTheFullHistory() {
        LocalDate from = START.plusDays(100);
        LocalDate to = START.plusDays(199);
        IndicatorResponse full = compute("50", null, null, null, null, null, null);
        IndicatorResponse cut = compute("50", null, null, null, null, from, to);

        assertEquals(100, cut.dates().length);
        assertEquals(from, cut.dates()[0]);
        assertEquals(to, cut.dates()[99]);
        assertEquals(full.closes()[100], cut.closes()[0]);
        assertNotNull(cut.sma().get(50)[0], "pas de trou de chauffe au debut de la fenetre");
        assertEquals(full.sma().get(50)[100], cut.sma().get(50)[0]);
        assertEquals(full.sma().get(50)[199], cut.sma().get(50)[99]);
    }

    @Test
    void openEndedWindow() {
        assertEquals(50, compute("20", null, null, null, null, START.plusDays(250), null).dates().length);
        assertEquals(11, compute("20", null, null, null, null, null, START.plusDays(10)).dates().length);
    }

    @Test
    void rejectsInvalidWindows() {
        assertThrows(IllegalArgumentException.class,
                () -> compute("20", null, null, null, null, START.plusDays(50), START.plusDays(10)));
        assertThrows(IllegalArgumentException.class,
                () -> compute("20", null, null, null, null, START.plusYears(5), null), "aucune donnee");
    }

    @Test
    void rejectsInvalidParameters() {
        assertRejected("abc", null, null, null, null);        // pas un nombre
        assertRejected("0", null, null, null, null);          // periode trop courte
        assertRejected("20.5", null, null, null, null);       // pas un entier
        assertRejected("300", null, null, null, null);        // plus long que l'historique
        assertRejected("5,6,7,8,9,10", null, null, null, null); // trop de periodes
        assertRejected(null, "-3", null, null, null);
        assertRejected(null, null, "14,21", null, null);      // une seule periode pour le RSI
        assertRejected(null, null, null, "12,26", null);      // il faut 3 valeurs
        assertRejected(null, null, null, "26,12,9", null);    // rapide >= lente
        assertRejected(null, null, null, null, "20");         // il manque k
        assertRejected(null, null, null, null, "20,0");       // k nul
    }

    @Test
    void errorMessagesNameTheParameter() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> compute(null, null, "abc", null, null, null, null));
        assertTrue(e.getMessage().startsWith("rsi"));
    }

    @Test
    void valuesAreRoundedTo6Decimals() {
        IndicatorResponse r = compute(null, null, "14", null, null, null, null);
        double v = r.rsi().values()[100];
        assertEquals(v, Math.round(v * 1e6) / 1e6);
    }
}
