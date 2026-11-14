package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Side;

/** Coherence SL / TP et validation des types d'ordre. */
class OrderRulesTest {

    private static final BigDecimal ENTRY = new BigDecimal("100");

    @Test
    void longRequiresStopBelowAndTargetAbove() {
        assertDoesNotThrow(() -> OrderRules.validateProtection(Side.BUY, ENTRY, bd("95"), bd("110")));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateProtection(Side.BUY, ENTRY, bd("101"), null));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateProtection(Side.BUY, ENTRY, bd("100"), null));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateProtection(Side.BUY, ENTRY, null, bd("99")));
    }

    @Test
    void shortRequiresTargetBelowAndStopAbove() {
        assertDoesNotThrow(() -> OrderRules.validateProtection(Side.SELL, ENTRY, bd("105"), bd("90")));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateProtection(Side.SELL, ENTRY, bd("95"), null));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateProtection(Side.SELL, ENTRY, null, bd("110")));
    }

    @Test
    void absentLevelsAreNotChecked() {
        assertDoesNotThrow(() -> OrderRules.validateProtection(Side.BUY, ENTRY, null, null));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateProtection(Side.BUY, ENTRY, bd("-1"), null));
    }

    @Test
    void limitAndStopRequireALevelMarketForbidsIt() {
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateType(OrderType.LIMIT, null));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateType(OrderType.STOP, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.validateType(OrderType.MARKET, bd("10")));
        assertDoesNotThrow(() -> OrderRules.validateType(OrderType.MARKET, null));
        assertDoesNotThrow(() -> OrderRules.validateType(OrderType.LIMIT, bd("10")));
    }

    @Test
    void quantityIsPositiveWithAtMostSixDecimals() {
        assertEquals(new BigDecimal("0.012300"), OrderRules.normalizeQuantity(bd("0.0123")));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.normalizeQuantity(bd("0")));
        assertThrows(IllegalArgumentException.class, () -> OrderRules.normalizeQuantity(bd("0.0000001")));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
