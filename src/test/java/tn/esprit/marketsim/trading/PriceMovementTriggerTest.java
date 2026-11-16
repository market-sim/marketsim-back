package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.ticker.MarketTickerService;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.market.AssetPriceTickRepository;
import tn.esprit.marketsim.market.AssetRepository;
import tn.esprit.marketsim.market.MarketEngineProperties;
import tn.esprit.marketsim.market.MarketPriceEngine;
import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;

/** Module 2 + moteur de prix du module 5 : un stop-loss se declenche tout seul quand le prix baisse. */
class PriceMovementTriggerTest {

    /** Aleatoire qui ne tire que des chocs negatifs : le prix baisse a chaque pas. */
    static final class FallingRandom extends Random {
        @Override
        public synchronized double nextGaussian() {
            return -3;
        }
    }

    @Test
    void stopLossDeclencheParUnMouvementDePrix() {
        Asset aapl = Asset.of("AAPL", "Apple", Asset.Type.STOCK, "100", 0);
        aapl.setId(1L);
        AssetRepository assets = mock(AssetRepository.class);
        when(assets.findAll()).thenReturn(List.of(aapl));
        AssetPriceTickRepository ticks = mock(AssetPriceTickRepository.class);
        lenient().when(ticks.findFirstBySymbolAndAtGreaterThanEqualOrderByAtAsc(anyString(), any())).thenReturn(Optional.empty());
        MarketPriceEngine market = new MarketPriceEngine(assets, ticks, mock(MarketTickerService.class),
                new MarketEngineProperties(), new FallingRandom(), ZoneOffset.UTC);

        // le trading lit le prix du moteur (meme chemin que AssetPriceProvider : Asset.price)
        PriceProvider prices = symbol -> aapl.getPrice();
        TradeOrderRepository repository = mock(TradeOrderRepository.class);
        TradeOrderService orders = mock(TradeOrderService.class);
        TradeOrder longAapl = new TradeOrder();
        longAapl.setId(42L);
        longAapl.setSymbol("AAPL");
        longAapl.setSide(Side.BUY);
        longAapl.setStatus(Status.OPEN);
        longAapl.setStopLoss(new BigDecimal("99"));
        when(repository.findTriggerCandidatesByOwner(7L)).thenReturn(List.of(longAapl));
        when(orders.processTrigger(42L)).thenReturn(OrderTriggerEngine.Action.STOP_LOSS);
        OrderTriggerService engine = new OrderTriggerService(repository, orders, prices, new TradingProperties());

        // au prix initial (100 > 99), rien ne se passe
        assertEquals(0, engine.sweepUser(7L));
        verify(orders, never()).processTrigger(42L);

        // le marche baisse pas a pas jusqu'a franchir le stop-loss
        Instant t = Instant.parse("2026-11-16T10:00:00Z");
        int steps = 0;
        while (aapl.getPrice().compareTo(new BigDecimal("99")) > 0 && steps < 1000) {
            market.step(t.plusSeconds(3L * steps++));
        }
        assertTrue(steps > 1 && steps < 1000, "le prix doit franchir le stop-loss en quelques pas : " + steps);
        assertEquals(OrderTriggerEngine.Action.STOP_LOSS, OrderTriggerEngine.evaluate(longAapl, aapl.getPrice()));
        assertEquals(1, engine.sweepUser(7L));
        verify(orders).processTrigger(42L);
    }
}
