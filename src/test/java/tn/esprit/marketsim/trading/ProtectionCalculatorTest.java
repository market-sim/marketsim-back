package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Random;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradingDtos.ProtectionSuggestion;

class ProtectionCalculatorTest {

    private static final BigDecimal FEE = new BigDecimal("0.001");
    private final TradingProperties.Protection cfg = new TradingProperties.Protection();

    @Test
    void longStopSousLEntreeEtTakeProfitAvecRatioDeux() {
        // σ journaliere 2 % : SL a 2σ = 4 % sous 100, TP a 8 % au-dessus
        ProtectionSuggestion s = ProtectionCalculator.suggest("AAPL", Side.BUY, new BigDecimal("100"), new BigDecimal("10"),
                0.3175, 0.02, "REAL", FEE, cfg);
        assertEquals(0, s.stopLoss().compareTo(new BigDecimal("96")));
        assertEquals(0, s.takeProfit().compareTo(new BigDecimal("108")));
        assertEquals("REAL", s.sigmaSource());
        // perte : 4 × 10 + frais (100×10 + 96×10) × 0,1 % = 40 + 1,96
        assertEquals(0, s.maxLoss().compareTo(new BigDecimal("41.96")));
        assertTrue(s.explanation().contains("réelle"));
    }

    @Test
    void shortStopAuDessusEtTakeProfitEnDessous() {
        ProtectionSuggestion s = ProtectionCalculator.suggest("TSLA", Side.SELL, new BigDecimal("200"), BigDecimal.ONE,
                0.30, 0.03, "MODEL", FEE, cfg);
        assertEquals(0, s.stopLoss().compareTo(new BigDecimal("212")));
        assertEquals(0, s.takeProfit().compareTo(new BigDecimal("176")));
        assertEquals("MODEL", s.sigmaSource());
        assertTrue(s.explanation().contains("modèle"));
    }

    @Test
    void distanceMinimaleSiLActifEstTresCalme() {
        ProtectionSuggestion s = ProtectionCalculator.suggest("EUR/USD", Side.BUY, new BigDecimal("1.0842"), new BigDecimal("1000"),
                0.0, 0.0, "MODEL", FEE, cfg);
        assertTrue(s.stopLoss().compareTo(new BigDecimal("1.0842")) < 0);
        assertEquals(0, s.stopLoss().compareTo(new BigDecimal("1.082"))); // 0,2 % puis arrondi a 4 decimales vers le bas
    }

    @Test
    void volatiliteExtremeBorneeTakeProfitShortPositif() {
        ProtectionSuggestion s = ProtectionCalculator.suggest("BTC/USD", Side.SELL, new BigDecimal("67000"), BigDecimal.ONE,
                5, 0.6, "MODEL", FEE, cfg);
        assertTrue(s.takeProfit().signum() > 0);
        assertTrue(s.stopLoss().compareTo(new BigDecimal("100500")) <= 0); // distance bornee a 50 %
    }

    @Test
    void toujoursCoherentAvecLesReglesDOrdre() {
        Random r = new Random(11);
        for (int i = 0; i < 5000; i++) {
            Side side = r.nextBoolean() ? Side.BUY : Side.SELL;
            BigDecimal entry = BigDecimal.valueOf(0.01 + r.nextDouble() * 80_000).setScale(6, java.math.RoundingMode.HALF_UP);
            double sd = r.nextDouble() * 0.1;
            ProtectionSuggestion s = ProtectionCalculator.suggest("X", side, entry, BigDecimal.ONE, sd * 15.87, sd,
                    r.nextBoolean() ? "REAL" : "MODEL", FEE, cfg);
            assertDoesNotThrow(() -> OrderRules.validateProtection(side, entry, s.stopLoss(), s.takeProfit()));
            assertTrue(s.maxLoss().signum() > 0);
        }
    }

    @Test
    void entreeInvalideRefusee() {
        assertThrows(IllegalArgumentException.class, () -> ProtectionCalculator.suggest("X", Side.BUY, BigDecimal.ZERO,
                BigDecimal.ONE, 0.3, 0.02, "MODEL", FEE, cfg));
    }
}
