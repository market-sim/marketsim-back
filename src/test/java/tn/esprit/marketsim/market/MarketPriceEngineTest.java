package tn.esprit.marketsim.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.ticker.MarketTickerService;
import tn.esprit.marketsim.agent.ticker.MarketTickerService.Category;
import tn.esprit.marketsim.agent.ticker.MarketTickerService.Quote;
import tn.esprit.marketsim.agent.ticker.MarketTickerService.Ticker;

class MarketPriceEngineTest {

    private AssetRepository assets;
    private AssetPriceTickRepository ticks;
    private MarketTickerService ticker;
    private MarketEngineProperties props;
    private Asset aapl;
    private Asset gold;

    @BeforeEach
    void setUp() {
        assets = mock(AssetRepository.class);
        ticks = mock(AssetPriceTickRepository.class);
        ticker = mock(MarketTickerService.class);
        props = new MarketEngineProperties();
        aapl = asset(1L, "AAPL", Asset.Type.STOCK, "192.45");
        gold = asset(2L, "GOLD", Asset.Type.COMMODITY, "2341.60");
        when(assets.findAll()).thenReturn(List.of(aapl, gold));
        lenient().when(ticks.findFirstBySymbolAndAtGreaterThanEqualOrderByAtAsc(anyString(), any())).thenReturn(Optional.empty());
    }

    private MarketPriceEngine engine(long seed) {
        return new MarketPriceEngine(assets, ticks, ticker, props, new Random(seed), ZoneOffset.UTC);
    }

    @Test
    void sansCoursReelLePrixSuitUneMarcheAleatoire() {
        MarketPriceEngine e = engine(1);
        Instant t = Instant.parse("2026-11-16T10:00:00Z");
        double before = aapl.getPrice().doubleValue();
        for (int i = 0; i < 50; i++) e.step(t.plusSeconds(3L * i));
        assertNotEquals(before, aapl.getPrice().doubleValue());
        assertTrue(aapl.getPrice().signum() > 0);
        assertTrue(Math.abs(aapl.getPrice().doubleValue() / before - 1) < 0.2);
        verify(assets, atLeastOnce()).updateQuote(any(), any(), any());
    }

    @Test
    void ancrageSurLeCoursReelDuBandeauAvecCorrespondanceDesSymboles() {
        Instant t = Instant.parse("2026-11-16T10:00:00Z");
        when(ticker.cachedTicker()).thenReturn(new Ticker(List.of(
                new Quote("AAPL", "Apple", Category.STOCK, 354.88, 1, 0.3, "USD"),
                new Quote("OR", "Or", Category.COMMODITY, 4000, 5, 0.1, "USD")), t.minusSeconds(30), false));
        MarketPriceEngine e = engine(2);
        for (int i = 0; i < 150; i++) {
            List<MarketPriceEngine.StepResult> r = e.step(t.plusSeconds(3L * i));
            assertTrue(r.stream().allMatch(MarketPriceEngine.StepResult::anchored));
            // reste a ±5 % du vrai cours (GOLD est ancre sur « OR »)
            assertTrue(Math.abs(aapl.getPrice().doubleValue() / 354.88 - 1) <= 0.0501);
            assertTrue(Math.abs(gold.getPrice().doubleValue() / 4000 - 1) <= 0.0501);
        }
        // le recalage a remis l'ouverture du jour au niveau du vrai cours : pas de « +84 % »
        assertTrue(Math.abs(aapl.getChangePct()) < 5.1);
    }

    @Test
    void coursReelTropAncienIgnore() {
        Instant t = Instant.parse("2026-11-16T10:00:00Z");
        when(ticker.cachedTicker()).thenReturn(new Ticker(List.of(
                new Quote("AAPL", "Apple", Category.STOCK, 354.88, 1, 0.3, "USD")), t.minusSeconds(3600), false));
        List<MarketPriceEngine.StepResult> r = engine(3).step(t);
        assertFalse(r.get(0).anchored());
        assertTrue(aapl.getPrice().doubleValue() < 250);
    }

    @Test
    void historiqueCompactUnPointParMinute() {
        MarketPriceEngine e = engine(4);
        Instant t = Instant.parse("2026-11-16T10:00:00Z");
        for (int i = 0; i < 40; i++) e.step(t.plusSeconds(3L * i)); // 2 minutes
        // 2 actifs × (t=0, t=60 s) = 4 points (pas 40 × 2)
        verify(ticks, times(4)).save(any());
        verify(ticks, times(2)).purgeBefore(any()); // une purge par pas qui a enregistre
    }

    @Test
    void jamaisDePrixNulMemeSansPrixInitial() {
        aapl.setPrice(null);
        engine(5).step(Instant.parse("2026-11-16T10:00:00Z"));
        assertTrue(aapl.getPrice().signum() > 0);
    }

    @Test
    void correspondanceDesSymboles() {
        assertEquals("BTC", MarketPriceEngine.tickerSymbol("BTC/USD"));
        assertEquals("PÉTROLE", MarketPriceEngine.tickerSymbol("wti"));
        assertEquals("MSFT", MarketPriceEngine.tickerSymbol("MSFT"));
        assertEquals("GOLD", MarketPriceEngine.assetSymbolByTicker().get("OR"));
    }

    static Asset asset(Long id, String symbol, Asset.Type type, String price) {
        Asset a = Asset.of(symbol, symbol, type, price, 0);
        a.setId(id);
        return a;
    }
}
