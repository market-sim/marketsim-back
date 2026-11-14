package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.trading.TradeOrder.Side;

/** Invariant comptable : variation du solde = P&L realise - frais, pour LONG et SHORT, quels que soient les prix. */
class TradeAccountingTest {

    private static final BigDecimal FEE = new BigDecimal("0.001");

    @Test
    void invariantHoldsForRandomLongAndShortTickets() {
        SplittableRandom rng = new SplittableRandom(2026);
        for (int i = 0; i < 2_000; i++) {
            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            BigDecimal qty = Money.price(BigDecimal.valueOf(rng.nextDouble(0.0001, 500)));
            BigDecimal entry = Money.price(BigDecimal.valueOf(rng.nextDouble(0.5, 70_000)));
            BigDecimal exit = Money.price(entry.multiply(BigDecimal.valueOf(rng.nextDouble(0.5, 1.5))));

            BigDecimal balance = new BigDecimal("1000000000.00");
            BigDecimal start = balance;
            BigDecimal openFee = TradeAccounting.fee(qty, entry, FEE);
            balance = balance.subtract(TradeAccounting.openCost(qty, entry, FEE));

            TradeOrder t = ticket(side, qty, entry);
            TradeAccounting.Close c = TradeAccounting.close(t, exit, FEE);
            balance = balance.add(c.credit());

            BigDecimal totalFees = openFee.add(c.closeFee());
            assertEquals(c.pnl().subtract(totalFees), balance.subtract(start), "ticket " + i + " " + side);
        }
    }

    @Test
    void shortPnlIsMirrorOfLong() {
        BigDecimal q = new BigDecimal("2");
        assertEquals(new BigDecimal("20.00"), TradeAccounting.pnl(Side.BUY, bd("100"), bd("110"), q));
        assertEquals(new BigDecimal("-20.00"), TradeAccounting.pnl(Side.SELL, bd("100"), bd("110"), q));
    }

    @Test
    void openValueMatchesEquityDefinition() {
        // LONG = qte x prixActuel ; SHORT = marge + (entree - prixActuel) x qte
        assertEquals(new BigDecimal("220.00"), TradeAccounting.openValue(ticket(Side.BUY, bd("2"), bd("100")), bd("110")));
        assertEquals(new BigDecimal("180.00"), TradeAccounting.openValue(ticket(Side.SELL, bd("2"), bd("100")), bd("110")));
    }

    private static TradeOrder ticket(Side side, BigDecimal qty, BigDecimal entry) {
        TradeOrder t = new TradeOrder();
        t.setSide(side);
        t.setQuantity(qty);
        t.setExecutedPrice(entry);
        t.setPrice(entry);
        t.setLegacy(false);
        return t;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
