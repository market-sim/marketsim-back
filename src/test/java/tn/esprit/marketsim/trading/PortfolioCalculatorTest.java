package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;
import tn.esprit.marketsim.trading.TradingDtos.PortfolioResponse;
import tn.esprit.marketsim.trading.TradingDtos.PositionDto;

/** Calculs du portefeuille (classe pure). */
class PortfolioCalculatorTest {

    private static final BigDecimal INITIAL = new BigDecimal("100000");

    @Test
    void equityCombinesCashLongAndShortTickets() {
        // LONG 10 AAPL @100 -> 110 ; SHORT 2 BTC @1000 -> 900 ; cash = 100000 - 1000 - 2000 = 97000 (frais ignores ici)
        List<TradeOrder> tickets = List.of(
                ticket("AAPL", Side.BUY, "10", "100", Status.OPEN),
                ticket("BTC/USD", Side.SELL, "2", "1000", Status.OPEN));
        Map<String, BigDecimal> marks = Map.of("AAPL", bd("110"), "BTC/USD", bd("900"));
        PortfolioResponse p = PortfolioCalculator.compute(bd("97000"), tickets, marks, INITIAL);

        // LONG vaut 1100 ; SHORT vaut marge 2000 + (1000 - 900) x 2 = 2200
        assertEquals(new BigDecimal("100300.00"), p.equity());
        assertEquals(new BigDecimal("3300.00"), p.investedValue());
        assertEquals(new BigDecimal("300.00"), p.unrealizedPnl());
        assertEquals(new BigDecimal("2900.00"), p.grossExposure());          // 1100 + 1800
        assertEquals(new BigDecimal("-700.00"), p.netExposure());            // 1100 - 1800
        assertEquals(new BigDecimal("0.30"), p.totalReturnPct());
        assertEquals(2, p.openPositions());
    }

    @Test
    void positionsAreAggregatedPerSymbolWithWeightedAverage() {
        List<TradeOrder> tickets = List.of(
                ticket("AAPL", Side.BUY, "10", "100", Status.OPEN),
                ticket("AAPL", Side.BUY, "30", "120", Status.OPEN),
                ticket("AAPL", Side.SELL, "5", "110", Status.OPEN),
                ticket("AAPL", Side.BUY, "99", "1", Status.CANCELLED));
        PortfolioResponse p = PortfolioCalculator.compute(bd("50000"), tickets, Map.of("AAPL", bd("115")), INITIAL);
        PositionDto pos = p.positions().get(0);

        assertEquals(1, p.positions().size());
        assertEquals(new BigDecimal("35.000000"), pos.netQuantity());
        assertEquals(new BigDecimal("40.000000"), pos.longQuantity());
        assertEquals(new BigDecimal("5.000000"), pos.shortQuantity());
        // (10x100 + 30x120 + 5x110) / 45 = 5150 / 45
        assertEquals(new BigDecimal("114.444444"), pos.avgEntryPrice());
        assertEquals(new BigDecimal("4025.00"), pos.marketValue());         // 35 x 115
        // P&L : 150 - 150 - 25 = -25
        assertEquals(new BigDecimal("-25.00"), pos.unrealizedPnl());
        assertEquals(3, pos.tickets());
    }

    @Test
    void realizedPnlAndFeesComeFromClosedTicketsAndPendingAreCounted() {
        TradeOrder closed = ticket("AAPL", Side.BUY, "1", "100", Status.CLOSED);
        closed.setRealizedPnl(new BigDecimal("12.50"));
        closed.setFees(new BigDecimal("0.21"));
        TradeOrder pending = ticket("AAPL", Side.BUY, "1", "90", Status.PENDING);
        PortfolioResponse p = PortfolioCalculator.compute(bd("100012.29"), List.of(closed, pending), Map.of(), INITIAL);

        assertEquals(new BigDecimal("12.50"), p.realizedPnl());
        assertEquals(new BigDecimal("0.21"), p.totalFees());
        assertEquals(new BigDecimal("100012.29"), p.equity());
        assertEquals(1, p.pendingOrders());
        assertEquals(0, p.openPositions());
    }

    @Test
    void missingPriceFallsBackToEntryAndLegacyTicketsOnlyAddPnl() {
        TradeOrder legacy = ticket("OLD", Side.BUY, "10", "50", Status.OPEN);
        legacy.setLegacy(true);
        PortfolioResponse p = PortfolioCalculator.compute(bd("1000"), List.of(legacy), Map.of(), INITIAL);
        assertEquals(new BigDecimal("1000.00"), p.equity(), "legacy au prix d'entree : P&L nul, pas de principal");
        assertEquals(new BigDecimal("50.00"), p.positions().get(0).weightPct());   // 500 / 1000
    }

    private static TradeOrder ticket(String symbol, Side side, String qty, String entry, Status status) {
        TradeOrder t = new TradeOrder();
        t.setSymbol(symbol);
        t.setSide(side);
        t.setQuantity(bd(qty));
        t.setExecutedPrice(bd(entry));
        t.setPrice(bd(entry));
        t.setStatus(status);
        t.setLegacy(false);
        return t;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
