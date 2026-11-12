package tn.esprit.marketsim.user.anomaly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LoginAnomalyModelTest {

    @Test
    void trainRequiresMinimumHistory() {
        List<LoginFeatureVector> few = new ArrayList<>();
        for (int i = 0; i < LoginAnomalyModel.MIN_HISTORY - 1; i++) {
            few.add(baseline(0.1 * (i % 3)));
        }
        assertThrows(IllegalArgumentException.class, () -> LoginAnomalyModel.train(few));
    }

    @Test
    void scoreIsBoundedAndOutlierIsHigher() {
        List<LoginFeatureVector> history = new ArrayList<>();
        for (int i = 0; i < LoginAnomalyModel.MIN_HISTORY + 5; i++) {
            history.add(baseline(0.02 * (i % 4)));
        }
        LoginAnomalyModel model = LoginAnomalyModel.train(history);
        assertEquals(LoginAnomalyModel.VERSION, model.version());

        double normal = model.score(baseline(0.01));
        double outlier = model.score(new LoginFeatureVector(
                -1.0, -1.0, 0.9, 1.0, 1.0, 1.0, 1.0, "other", 8, 1));

        assertTrue(normal >= 0 && normal <= 100);
        assertTrue(outlier >= 0 && outlier <= 100);
        assertTrue(outlier > normal);
        assertEquals(LoginRiskLevel.HIGH, LoginRiskLevel.fromScore(75));
        assertEquals(LoginRiskLevel.MEDIUM, LoginRiskLevel.fromScore(40));
        assertEquals(LoginRiskLevel.LOW, LoginRiskLevel.fromScore(10));
        assertTrue(model.explain(outlier > normal ? history.get(0) : history.get(0), normal).size() >= 1);
    }

    @Test
    void riskThresholdsMatchTicket() {
        assertEquals(LoginRiskLevel.LOW, LoginRiskLevel.fromScore(39.9));
        assertEquals(LoginRiskLevel.MEDIUM, LoginRiskLevel.fromScore(40));
        assertEquals(LoginRiskLevel.MEDIUM, LoginRiskLevel.fromScore(69.9));
        assertEquals(LoginRiskLevel.HIGH, LoginRiskLevel.fromScore(70));
    }

    private static LoginFeatureVector baseline(double noise) {
        return new LoginFeatureVector(
                0.5 + noise,
                0.5 - noise,
                0.4,
                0.3,
                0.05 + noise * 0.1,
                0.0,
                0.15,
                "chrome",
                0,
                120);
    }
}
