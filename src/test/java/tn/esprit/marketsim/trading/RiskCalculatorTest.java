package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradingDtos.RiskAssessment;
import tn.esprit.marketsim.trading.TradingDtos.RiskCheck;
import tn.esprit.marketsim.trading.TradingDtos.RiskLevel;

/** Regles dures, score et VaR (classe pure). */
class RiskCalculatorTest {

    private static final TradingProperties.Risk LIMITS = new TradingProperties.Risk();

    @Test
    void smallStockOrderIsLowRiskAndDeterministic() {
        // 10 AAPL @ 192.45 sur un compte neuf de 100 000
        RiskAssessment a = RiskCalculator.assess(input(Side.BUY, "10", "192.45", "100000", "100000", "0", "0", 0, 0.30, true));
        assertEquals(RiskLevel.LOW, a.level());
        // 30 x 0.0481 + 25 x 0.077 + 20 x 0.375 + 15 x 0.0192 + 10 x 0.0193 ≈ 11
        assertEquals(11, a.score());
        assertEquals(a.score(), RiskCalculator.assess(input(Side.BUY, "10", "192.45", "100000", "100000", "0", "0", 0, 0.30, true)).score());
        assertTrue(a.checks().stream().allMatch(RiskCheck::passed));
        assertEquals(new BigDecimal("1924.50"), a.metrics().notional());
        assertEquals(new BigDecimal("1.92"), a.metrics().orderPctOfEquity());
    }

    @Test
    void largeCryptoOrderIsHighRiskWithStopLossWarningButNotBlocked() {
        // 0.35 BTC @ 67 240 = 23 534 (23,5 % de l'equity) sans stop-loss, compte deja investi a 40 % ailleurs :
        // 30 x 0.588 + 25 x 0.941 + 20 x 0.875 + 15 x 0.635 + 10 x 0.636 ≈ 75
        RiskAssessment a = RiskCalculator.assess(input(Side.BUY, "0.35", "67240", "60000", "100000", "0", "40000", 3, 0.70, false));
        assertEquals(75, a.score());
        assertEquals(RiskLevel.HIGH, a.level());
        RiskCheck warning = find(a, "STOP_LOSS_ADVISED");
        assertFalse(warning.passed());
        assertFalse(warning.blocking());
        assertFalse(a.blocked());
    }

    @Test
    void orderAboveMaxPositionIsBlocked() {
        RiskAssessment a = RiskCalculator.assess(input(Side.BUY, "200", "192.45", "100000", "100000", "0", "0", 0, 0.30, true));
        assertEquals(RiskLevel.BLOCKED, a.level());               // 38 490 = 38,5 % > 25 %
        assertFalse(find(a, "MAX_POSITION_SIZE").passed());
        assertTrue(a.blockingReasons().contains("Taille de l'ordre"));
    }

    @Test
    void insufficientBalanceIsBlocked() {
        RiskAssessment a = RiskCalculator.assess(input(Side.SELL, "10", "192.45", "1000", "100000", "0", "0", 0, 0.30, true));
        assertFalse(find(a, "BALANCE").passed());
        assertTrue(a.blocked());
    }

    @Test
    void symbolConcentrationCashReserveAndSlotsAreHardRules() {
        // deja 30 000 sur le symbole + 15 000 = 45 % > 40 %
        RiskAssessment conc = RiskCalculator.assess(input(Side.BUY, "100", "150", "70000", "100000", "30000", "30000", 1, 0.3, true));
        assertFalse(find(conc, "SYMBOL_CONCENTRATION").passed());
        assertTrue(conc.blocked());

        // liquidites 6 000, ordre 2 000 -> 4 % < 5 %
        RiskAssessment reserve = RiskCalculator.assess(input(Side.BUY, "10", "200", "6000", "100000", "0", "94000", 3, 0.3, true));
        assertFalse(find(reserve, "MIN_CASH_RESERVE").passed());

        RiskAssessment slots = RiskCalculator.assess(input(Side.BUY, "1", "100", "100000", "100000", "0", "0", 20, 0.3, true));
        assertFalse(find(slots, "MAX_OPEN_POSITIONS").passed());
        assertTrue(slots.blocked());
    }

    @Test
    void scoreGrowsWithVolatilityAndSize() {
        int stock = RiskCalculator.assess(input(Side.BUY, "50", "100", "100000", "100000", "0", "0", 0, 0.30, true)).score();
        int crypto = RiskCalculator.assess(input(Side.BUY, "50", "100", "100000", "100000", "0", "0", 0, 0.70, true)).score();
        int bigger = RiskCalculator.assess(input(Side.BUY, "200", "100", "100000", "100000", "0", "0", 0, 0.30, true)).score();
        assertTrue(crypto > stock);
        assertTrue(bigger > stock);
    }

    @Test
    void parametricOrderVarIsOneSixFourFiveSigma() {
        // σ annuelle 0,252 -> σ journaliere ≈ 0,01587 ; VaR 95 % de 10 000 ≈ 261
        BigDecimal var = RiskCalculator.orderVar95(Side.BUY, new BigDecimal("10000"), 0.252, null);
        assertEquals(261.1, var.doubleValue(), 0.5);
    }

    @Test
    void historicalOrderVarIsMirroredForShorts() {
        double[] sorted = new double[100];
        for (int i = 0; i < 100; i++) sorted[i] = -0.05 + i * 0.0006;      // de -5 % a +0,94 %
        BigDecimal longVar = RiskCalculator.orderVar95(Side.BUY, new BigDecimal("1000"), 0.3, sorted);
        BigDecimal shortVar = RiskCalculator.orderVar95(Side.SELL, new BigDecimal("1000"), 0.3, sorted);
        assertTrue(longVar.doubleValue() > shortVar.doubleValue(), "la queue gauche penalise le LONG");
    }

    @Test
    void portfolioMath() {
        // une seule ligne : σp = |e| σ
        assertEquals(100, RiskCalculator.parametricSigma(new double[] {10_000}, new double[] {0.01}, 0.5), 1e-9);
        // deux lignes identiques, correlation 1 : σp = 200 ; LONG + SHORT parfaitement correles : 0
        assertEquals(200, RiskCalculator.parametricSigma(new double[] {10_000, 10_000}, new double[] {0.01, 0.01}, 1), 1e-9);
        assertEquals(0, RiskCalculator.parametricSigma(new double[] {10_000, -10_000}, new double[] {0.01, 0.01}, 1), 1e-9);

        assertEquals(1, RiskCalculator.hhi(new double[] {5000}), 1e-12);
        assertEquals(0.5, RiskCalculator.hhi(new double[] {5000, 5000}), 1e-12);
        assertEquals(100, RiskCalculator.diversification(0.5, 2), 1e-9);
        assertEquals(0, RiskCalculator.diversification(1, 1), 1e-9);
    }

    @Test
    void historicalPnlUsesOnlyCommonDates() {
        Map<LocalDate, Double> a = new LinkedHashMap<>();
        Map<LocalDate, Double> b = new LinkedHashMap<>();
        LocalDate d0 = LocalDate.of(2026, 1, 5);
        a.put(d0, 100.0); a.put(d0.plusDays(1), 110.0); a.put(d0.plusDays(2), 99.0);
        b.put(d0, 50.0); b.put(d0.plusDays(2), 55.0);                    // d0+1 manquant pour b
        double[] pnl = RiskCalculator.historicalPnl(new double[] {1000, -1000}, List.of(a, b), 250);
        assertEquals(1, pnl.length);
        // a : 99/100 - 1 = -1 % ; b : +10 % ; P&L = -10 + (-100) = -110
        assertEquals(-110, pnl[0], 1e-9);
    }

    private static RiskCheck find(RiskAssessment a, String name) {
        return a.checks().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static RiskCalculator.Input input(Side side, String qty, String price, String cash, String equity,
                                              String symbolGross, String gross, int active, double sigma, boolean sl) {
        return new RiskCalculator.Input(side, new BigDecimal(qty), new BigDecimal(price), new BigDecimal("0.001"), sl,
                new BigDecimal(cash), new BigDecimal(equity), new BigDecimal(symbolGross), new BigDecimal(gross),
                active, sigma, null, "MODEL", LIMITS);
    }
}
