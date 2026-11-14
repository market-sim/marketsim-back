package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;

/** Balayage du moteur : seuls les tickets declenches sont traites, une erreur n'arrete pas le balayage. */
@ExtendWith(MockitoExtension.class)
class OrderTriggerServiceTest {

    @Mock
    private TradeOrderRepository repository;
    @Mock
    private TradeOrderService orders;
    @Mock
    private PriceProvider prices;

    @Test
    void onlyTriggeredTicketsAreProcessedEachInItsOwnTransaction() {
        OrderTriggerService engine = new OrderTriggerService(repository, orders, prices, new TradingProperties());
        TradeOrder hitSl = open(1L, "90", null);
        TradeOrder quiet = open(2L, "50", "200");
        TradeOrder raced = open(3L, "95", null);
        when(prices.currentPrice("AAPL")).thenReturn(new BigDecimal("89"));
        when(repository.findTriggerCandidatesByOwner(7L)).thenReturn(List.of(hitSl, quiet, raced));
        when(orders.processTrigger(1L)).thenReturn(OrderTriggerEngine.Action.STOP_LOSS);
        when(orders.processTrigger(3L)).thenThrow(new OptimisticLockingFailureException("deja traite"));

        assertEquals(1, engine.sweepUser(7L));
        verify(orders, never()).processTrigger(2L);
    }

    @Test
    void disabledScheduledSweepDoesNothing() {
        TradingProperties props = new TradingProperties();
        props.getEngine().setEnabled(false);
        new OrderTriggerService(repository, orders, prices, props).scheduledSweep();
        verify(orders, never()).processTrigger(anyLong());
    }

    private static TradeOrder open(Long id, String sl, String tp) {
        TradeOrder o = new TradeOrder();
        o.setId(id);
        o.setSymbol("AAPL");
        o.setSide(Side.BUY);
        o.setStatus(Status.OPEN);
        o.setStopLoss(sl == null ? null : new BigDecimal(sl));
        o.setTakeProfit(tp == null ? null : new BigDecimal(tp));
        return o;
    }
}
