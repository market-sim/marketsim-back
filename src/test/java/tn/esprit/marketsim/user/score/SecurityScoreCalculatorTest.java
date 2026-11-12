package tn.esprit.marketsim.user.score;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;

class SecurityScoreCalculatorTest {

    @Test
    void weightsAndBandsAreStable() {
        assertEquals(3, SecurityScoreCalculator.weight(LoginRiskLevel.HIGH));
        assertEquals(2, SecurityScoreCalculator.weight(LoginRiskLevel.MEDIUM));
        assertEquals(1, SecurityScoreCalculator.weight(LoginRiskLevel.LOW));

        int allLow = SecurityScoreCalculator.score(1 + 1 + 1, 3);
        int mixed = SecurityScoreCalculator.score(2 + 1 + 1, 3);
        int allHigh = SecurityScoreCalculator.score(3 + 3 + 3, 3);

        assertEquals(33, allLow);
        assertEquals(44, mixed);
        assertEquals(100, allHigh);
        assertEquals(LoginRiskLevel.LOW, SecurityScoreCalculator.band(allLow));
        assertEquals(LoginRiskLevel.MEDIUM, SecurityScoreCalculator.band(mixed));
        assertEquals(LoginRiskLevel.MEDIUM, SecurityScoreCalculator.band(69));
        assertEquals(LoginRiskLevel.HIGH, SecurityScoreCalculator.band(70));
        assertEquals(allLow, SecurityScoreCalculator.score(3, 3));
    }

    @Test
    void refusesScoreWithoutMinimumEvidence() {
        assertThrows(IllegalArgumentException.class, () -> SecurityScoreCalculator.score(3, 2));
    }
}
