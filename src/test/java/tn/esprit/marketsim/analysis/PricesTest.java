package tn.esprit.marketsim.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.realdata.PriceSeries;

/** Clotures normalisees a 6 decimales (comme le cache du module 6) et empreinte d'une periode. */
class PricesTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 1);

    private static PriceSeries series(int n, IntToDoubleFunction price) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dates.add(START.plusDays(i));
            closes.add(price.applyAsDouble(i));
        }
        return PriceSeries.of("TEST", "YAHOO", dates, closes);
    }

    /** Clotures a 8 decimales, comme celles renvoyees juste apres un telechargement. */
    private static PriceSeries raw(int n) {
        return series(n, i -> 100 + i * 0.123456789 + Math.sin(i) * 1e-7);
    }

    @Test
    void closesAreRoundedToSixDecimalsExactlyLikeTheCache() {
        PriceSeries raw = raw(50);
        PriceSeries cached = PriceSeries.decode(raw.symbol(), raw.source(), raw.encode());   // relecture du cache

        PriceSeries normalized = Prices.normalized(raw);

        assertArrayEquals(cached.closes(), normalized.closes(), 0.0);
        assertArrayEquals(raw.dates(), normalized.dates());
        assertEquals(raw.hash(), normalized.hash(), "meme empreinte que la serie d'origine");
        assertEquals(100.123457, normalized.closes()[1], 0.0, "100,1234567890... -> 100,123457");
        assertNotEquals(raw.closes()[1], normalized.closes()[1]);
    }

    @Test
    void normalizingTwiceChangesNothing() {
        PriceSeries once = Prices.normalized(raw(50));
        PriceSeries twice = Prices.normalized(once);
        assertArrayEquals(once.closes(), twice.closes(), 0.0);
        assertEquals(once.hash(), twice.hash());
    }

    @Test
    void thePeriodHashOnlyDependsOnThePeriod() {
        PriceSeries s = Prices.normalized(raw(100));
        LocalDate from = START.plusDays(10);
        LocalDate to = START.plusDays(40);
        String hash = Prices.periodHash(s, from, to);

        // la serie s'allonge chaque jour : la periode garde la meme empreinte
        assertEquals(hash, Prices.periodHash(Prices.normalized(raw(300)), from, to));
        // une cloture modifiee hors de la periode ne change rien
        assertEquals(hash, Prices.periodHash(Prices.normalized(series(100, i -> i == 5 ? 1 : 100 + i * 0.123456789
                + Math.sin(i) * 1e-7)), from, to));
        // une cloture ajustee dans la periode change l'empreinte
        assertNotEquals(hash, Prices.periodHash(Prices.normalized(series(100, i -> i == 20 ? 1 : 100 + i * 0.123456789
                + Math.sin(i) * 1e-7)), from, to));
        // une autre periode a une autre empreinte
        assertNotEquals(hash, Prices.periodHash(s, from, to.plusDays(1)));
        // un ecart sous la 7e decimale (avant arrondi) ne change pas l'empreinte
        assertEquals(hash, Prices.periodHash(raw(100), from, to));
    }
}
