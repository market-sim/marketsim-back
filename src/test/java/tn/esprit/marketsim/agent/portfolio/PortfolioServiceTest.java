package tn.esprit.marketsim.agent.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PortfolioServiceTest {

    @Test
    void weightsAreNormalisedAndDefaultToEqualParts() {
        var equal = PortfolioService.weights(List.of("AAPL", "TSLA"), null);
        assertEquals(0.5, equal.get("AAPL"), 1e-9);
        var custom = PortfolioService.weights(List.of("AAPL", "TSLA", "BTC"), Map.of("AAPL", 3.0, "TSLA", 1.0));
        assertEquals(0.75, custom.get("AAPL"), 1e-9);
        assertEquals(0.0, custom.get("BTC"), 1e-9);
    }

    @Test
    void correlationOfIdenticalAndOppositeSeries() {
        double[] a = {0.01, -0.02, 0.03, 0.0, -0.01};
        double[] b = {-0.01, 0.02, -0.03, 0.0, 0.01};
        assertEquals(1.0, PortfolioService.correlation(a, a), 1e-9);
        assertEquals(-1.0, PortfolioService.correlation(a, b), 1e-9);
        assertEquals(1.0, PortfolioService.averageCorrelation(List.of(a)), 1e-9);
    }

    @Test
    void verdictExplainsDiversification() {
        String v = PortfolioService.verdict(0.08, 0.05, -0.10, -0.25, 0.2, 3);
        assertTrue(v.contains("bat"));
        assertTrue(v.contains("10,0 %"));
        assertTrue(v.contains("peu corrélés"));
        assertTrue(PortfolioService.verdict(0, 0, 0, 0, 1, 1).contains("Un seul actif"));
    }
}
