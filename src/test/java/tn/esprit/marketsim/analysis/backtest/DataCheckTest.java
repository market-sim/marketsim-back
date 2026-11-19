package tn.esprit.marketsim.analysis.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.Prices;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.DataCheckResult;
import tn.esprit.marketsim.analysis.backtest.DataCheck.Status;

/** Comparaison de l'empreinte enregistree a celle des cours actuels, sur la meme periode seulement. */
class DataCheckTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    private static final LocalDate FROM = START.plusDays(20);
    private static final LocalDate TO = START.plusDays(80);

    /** n seances a partir de START + offset (jours consecutifs). */
    private static PriceSeries series(int offset, int n, IntToDoubleFunction price) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dates.add(START.plusDays(offset + i));
            closes.add(price.applyAsDouble(offset + i));
        }
        return Prices.normalized(PriceSeries.of("TEST", "YAHOO", dates, closes));
    }

    private static double price(int day) {
        return 100 + day * 0.37;
    }

    /** Empreinte enregistree au moment du backtest, sur une serie de 100 jours. */
    private static final String SAVED = Prices.periodHash(series(0, 100, DataCheckTest::price), FROM, TO);

    @Test
    void sameClosesOnThePeriodGiveSame() {
        DataCheckResult r = DataCheck.check(SAVED, FROM, TO, series(0, 100, DataCheckTest::price));
        assertEquals(Status.SAME, r.status());
        assertEquals(START, r.currentFirst());
        assertEquals(START.plusDays(99), r.currentLast());
    }

    @Test
    void aLongerSeriesIsStillTheSame() {
        // la serie s'est allongee de 200 jours depuis : la periode n'a pas change
        assertEquals(Status.SAME, DataCheck.check(SAVED, FROM, TO, series(0, 300, DataCheckTest::price)).status());
    }

    @Test
    void anAdjustmentOutsideThePeriodIsNotSeen() {
        PriceSeries adjustedBefore = series(0, 100, d -> d < 20 ? price(d) * 0.98 : price(d));
        assertEquals(Status.SAME, DataCheck.check(SAVED, FROM, TO, adjustedBefore).status());
    }

    @Test
    void adjustedClosesOnThePeriodGiveChanged() {
        // dividende : toutes les clotures anterieures au 60e jour baissent de 1 %
        PriceSeries adjusted = series(0, 100, d -> d < 60 ? price(d) * 0.99 : price(d));
        assertEquals(Status.CHANGED, DataCheck.check(SAVED, FROM, TO, adjusted).status());
        // une seance retiree par la source change aussi l'empreinte
        assertEquals(Status.CHANGED, DataCheck.check(SAVED, FROM, TO, withoutDay(50)).status());
    }

    /** Serie de 100 jours sans la seance du jour {@code skipped}. */
    private static PriceSeries withoutDay(int skipped) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            if (i == skipped) continue;
            dates.add(START.plusDays(i));
            closes.add(price(i));
        }
        return Prices.normalized(PriceSeries.of("TEST", "YAHOO", dates, closes));
    }

    @Test
    void aSeriesThatNoLongerCoversThePeriodIsNotCovered() {
        // la fenetre de 10 ans de la source a avance : l'historique commence apres le debut de la periode
        DataCheckResult r = DataCheck.check(SAVED, FROM, TO, series(30, 200, DataCheckTest::price));
        assertEquals(Status.NOT_COVERED, r.status());
        assertEquals(START.plusDays(30), r.currentFirst());
        // ou il s'arrete avant la fin de la periode
        assertEquals(Status.NOT_COVERED, DataCheck.check(SAVED, FROM, TO, series(0, 60, DataCheckTest::price)).status());
    }

    @Test
    void anOldRecordWithoutPeriodHashIsUnverifiable() {
        DataCheckResult r = DataCheck.check(null, FROM, TO, series(0, 100, DataCheckTest::price));
        assertEquals(Status.UNVERIFIABLE, r.status());
        assertNull(r.currentFirst());
    }

    @Test
    void missingCurrentPricesAreUnavailable() {
        assertEquals(Status.UNAVAILABLE, DataCheck.check(SAVED, FROM, TO, null).status());
    }
}
