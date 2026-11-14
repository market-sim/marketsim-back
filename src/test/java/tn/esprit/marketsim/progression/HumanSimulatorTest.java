package tn.esprit.marketsim.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.progression.HumanSimulator.Order;
import tn.esprit.marketsim.progression.HumanSimulator.Result;
import tn.esprit.marketsim.progression.HumanSimulator.Side;

class HumanSimulatorTest {

    private static final double[] PRICES = {100, 110, 121, 100, 90, 99};

    @Test
    void noOrdersKeepsCapital() {
        Result r = HumanSimulator.run(PRICES, 1000, List.of(), 0.001, 0.05);
        assertEquals(1000, r.finalEquity(), 1e-9);
        assertEquals(0, r.tradeReturns().size());
    }

    @Test
    void buyAndSellWithoutFees() {
        Result r = HumanSimulator.run(PRICES, 1000,
                List.of(new Order(0, Side.BUY, 1.0), new Order(2, Side.SELL, null)), 0, 0.05);
        assertEquals(1210, r.finalEquity(), 1e-6);
        assertEquals(1, r.tradeReturns().size());
        assertEquals(0.21, r.tradeReturns().get(0), 1e-9);
        assertEquals(1, r.disciplinedTrades());
    }

    @Test
    void feesAreChargedOnEachOrder() {
        Result r = HumanSimulator.run(PRICES, 1000,
                List.of(new Order(0, Side.BUY, 1.0), new Order(1, Side.SELL, null)), 0.01, 0.05);
        // achat : 990 investis -> 9,9 unites ; vente : 1089 - 1 % = 1078,11
        assertEquals(1078.11, r.finalEquity(), 1e-6);
        assertEquals(20.89, r.fees(), 1e-6);
    }

    @Test
    void openPositionIsValuedAtLastPriceAndCountsAsATrade() {
        Result r = HumanSimulator.run(PRICES, 1000, List.of(new Order(2, Side.BUY, 0.5)), 0, 0.05);
        assertEquals(500 + 500.0 / 121 * 99, r.finalEquity(), 1e-6);
        assertEquals(1, r.tradeReturns().size());
        // perte de -25,6 % au pire contre un stop de 5 % : trade indiscipline
        assertEquals(0, r.disciplinedTrades());
        assertEquals((500 + 500.0 / 121 * 90) / 1000 - 1, r.maxDrawdown(), 1e-6);
    }

    @Test
    void invalidOrdersAreIgnored() {
        Result r = HumanSimulator.run(PRICES, 1000,
                List.of(new Order(-1, Side.BUY, 1.0), new Order(99, Side.BUY, 1.0), new Order(3, Side.SELL, null)), 0, 0.05);
        assertEquals(1000, r.finalEquity(), 1e-9);
        assertEquals(0, r.orders());
    }
}
