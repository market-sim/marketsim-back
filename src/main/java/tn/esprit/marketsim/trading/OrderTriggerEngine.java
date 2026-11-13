package tn.esprit.marketsim.trading;

import java.math.BigDecimal;

import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;

/**
 * Regles de declenchement (pures, testables sans Spring).
 * <pre>
 *   PENDING  LIMIT BUY  : prix &lt;= limite        PENDING  STOP BUY  : prix &gt;= niveau
 *            LIMIT SELL : prix &gt;= limite                 STOP SELL : prix &lt;= niveau
 *   OPEN LONG  (BUY)  : STOP_LOSS si prix &lt;= SL ; TAKE_PROFIT si prix &gt;= TP
 *   OPEN SHORT (SELL) : STOP_LOSS si prix &gt;= SL ; TAKE_PROFIT si prix &lt;= TP
 * </pre>
 * Si SL et TP sont tous deux franchis (gap de prix), le stop-loss l'emporte (hypothese prudente). L'execution se
 * fait au prix observe (pas au niveau), comme un ordre au marche declenche.
 */
public final class OrderTriggerEngine {

    public enum Action { NONE, FILL, STOP_LOSS, TAKE_PROFIT }

    private OrderTriggerEngine() {
    }

    public static Action evaluate(TradeOrder o, BigDecimal price) {
        if (o == null || price == null || price.signum() <= 0) return Action.NONE;
        if (o.getStatus() == Status.PENDING) {
            return pendingTriggered(o, price) ? Action.FILL : Action.NONE;
        }
        if (o.getStatus() == Status.OPEN) {
            boolean isLong = o.getSide() == Side.BUY;
            BigDecimal sl = o.getStopLoss();
            BigDecimal tp = o.getTakeProfit();
            if (sl != null && (isLong ? price.compareTo(sl) <= 0 : price.compareTo(sl) >= 0)) return Action.STOP_LOSS;
            if (tp != null && (isLong ? price.compareTo(tp) >= 0 : price.compareTo(tp) <= 0)) return Action.TAKE_PROFIT;
        }
        return Action.NONE;
    }

    private static boolean pendingTriggered(TradeOrder o, BigDecimal price) {
        BigDecimal level = o.getLimitPrice();
        if (level == null) return false;
        boolean buy = o.getSide() == Side.BUY;
        if (o.getOrderType() == OrderType.LIMIT) {
            return buy ? price.compareTo(level) <= 0 : price.compareTo(level) >= 0;
        }
        if (o.getOrderType() == OrderType.STOP) {
            return buy ? price.compareTo(level) >= 0 : price.compareTo(level) <= 0;
        }
        return false;
    }
}
