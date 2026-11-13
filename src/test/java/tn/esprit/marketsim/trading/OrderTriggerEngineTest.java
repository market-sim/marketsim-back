package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.trading.OrderTriggerEngine.Action;
import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;

/** Regles de declenchement LIMIT / STOP / SL / TP (classe pure, sans Spring). */
class OrderTriggerEngineTest {

    @Test
    void limitBuyFillsAtOrBelowLevel() {
        TradeOrder o = pending(OrderType.LIMIT, Side.BUY, "100");
        assertEquals(Action.NONE, OrderTriggerEngine.evaluate(o, bd("100.01")));
        assertEquals(Action.FILL, OrderTriggerEngine.evaluate(o, bd("100")));
        assertEquals(Action.FILL, OrderTriggerEngine.evaluate(o, bd("95")));
    }

    @Test
    void limitSellFillsAtOrAboveLevel() {
        TradeOrder o = pending(OrderType.LIMIT, Side.SELL, "100");
        assertEquals(Action.NONE, OrderTriggerEngine.evaluate(o, bd("99.99")));
        assertEquals(Action.FILL, OrderTriggerEngine.evaluate(o, bd("100")));
    }

    @Test
    void stopBuyFillsAtOrAboveLevelAndStopSellAtOrBelow() {
        assertEquals(Action.NONE, OrderTriggerEngine.evaluate(pending(OrderType.STOP, Side.BUY, "100"), bd("99")));
        assertEquals(Action.FILL, OrderTriggerEngine.evaluate(pending(OrderType.STOP, Side.BUY, "100"), bd("101")));
        assertEquals(Action.NONE, OrderTriggerEngine.evaluate(pending(OrderType.STOP, Side.SELL, "100"), bd("101")));
        assertEquals(Action.FILL, OrderTriggerEngine.evaluate(pending(OrderType.STOP, Side.SELL, "100"), bd("99")));
    }

    @Test
    void longStopLossAndTakeProfit() {
        TradeOrder o = open(Side.BUY, "90", "120");
        assertEquals(Action.NONE, OrderTriggerEngine.evaluate(o, bd("100")));
        assertEquals(Action.STOP_LOSS, OrderTriggerEngine.evaluate(o, bd("90")));
        assertEquals(Action.TAKE_PROFIT, OrderTriggerEngine.evaluate(o, bd("121")));
    }

    @Test
    void shortStopLossAndTakeProfitAreMirrored() {
        TradeOrder o = open(Side.SELL, "110", "80");
        assertEquals(Action.NONE, OrderTriggerEngine.evaluate(o, bd("100")));
        assertEquals(Action.STOP_LOSS, OrderTriggerEngine.evaluate(o, bd("110")));
        assertEquals(Action.TAKE_PROFIT, OrderTriggerEngine.evaluate(o, bd("79")));
    }

    @Test
    void stopLossWinsWhenBothLevelsAreCrossedAndTerminalTicketsNeverTrigger() {
        TradeOrder weird = open(Side.BUY, "120", "90");     // niveaux inverses : les deux franchis a 100
        assertEquals(Action.STOP_LOSS, OrderTriggerEngine.evaluate(weird, bd("100")));
        TradeOrder closed = open(Side.BUY, "90", "120");
        closed.setStatus(Status.CLOSED);
        assertEquals(Action.NONE, OrderTriggerEngine.evaluate(closed, bd("50")));
        assertEquals(Action.NONE, OrderTriggerEngine.evaluate(open(Side.BUY, null, null), bd("1")));
    }

    private static TradeOrder pending(OrderType type, Side side, String level) {
        TradeOrder o = new TradeOrder();
        o.setStatus(Status.PENDING);
        o.setOrderType(type);
        o.setSide(side);
        o.setLimitPrice(bd(level));
        return o;
    }

    private static TradeOrder open(Side side, String sl, String tp) {
        TradeOrder o = new TradeOrder();
        o.setStatus(Status.OPEN);
        o.setSide(side);
        o.setStopLoss(sl == null ? null : bd(sl));
        o.setTakeProfit(tp == null ? null : bd(tp));
        return o;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
