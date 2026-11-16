package tn.esprit.marketsim.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.progression.ProgressionRules.CoachMode;
import tn.esprit.marketsim.progression.ProgressionRules.GameOutcome;
import tn.esprit.marketsim.progression.ProgressionRules.GuideMode;
import tn.esprit.marketsim.progression.ProgressionRules.Stats;
import tn.esprit.marketsim.user.UserLevel;

class ProgressionRulesTest {

    /** Un trader regulier : 60 % de trades gagnants, pertes coupees tot, baisses moderees. */
    private static Stats skilled(double balance, int games, int trades) {
        int wins = (int) Math.round(trades * 0.6);
        return new Stats(balance, games, trades, wins, wins * 0.08, (trades - wins) * 0.03, trades, games * 0.08);
    }

    @Test
    void startsAsBeginnerWithTheSingleStartingCapital() {
        Stats s = Stats.fresh();
        assertEquals(100_000.0, s.balance()); // capital unique, le meme que le trading
        assertEquals(UserLevel.DEBUTANT, ProgressionRules.levelFor(s));
        assertEquals(0, ProgressionRules.skillScore(s));
        assertFalse(ProgressionRules.bankrupt(s));
    }

    @Test
    void levelClimbsWithMoneyAndSkill() {
        assertEquals(UserLevel.INTERMEDIAIRE, ProgressionRules.levelFor(skilled(130000, 3, 6)));
        assertEquals(UserLevel.AVANCE, ProgressionRules.levelFor(skilled(200000, 8, 16)));
        assertEquals(UserLevel.EXPERT, ProgressionRules.levelFor(skilled(350000, 12, 32)));
    }

    @Test
    void fewTradesGiveAReducedScore() {
        Stats one = new Stats(110000, 1, 1, 1, 0.1, 0, 1, 0.01);
        Stats ten = new Stats(110000, 3, 10, 10, 1.0, 0, 10, 0.03);
        org.junit.jupiter.api.Assertions.assertTrue(ProgressionRules.skillScore(one) <= 10);
        org.junit.jupiter.api.Assertions.assertTrue(ProgressionRules.skillScore(ten) >= 90);
    }

    @Test
    void moneyAloneIsNotEnough() {
        // beaucoup d'argent gagne en un coup de chance, mais un seul trade et une seule partie
        Stats lucky = new Stats(500000, 1, 1, 1, 4, 0, 0, 0.5);
        assertEquals(UserLevel.DEBUTANT, ProgressionRules.levelFor(lucky));
    }

    @Test
    void skillAloneIsNotEnough() {
        assertEquals(UserLevel.DEBUTANT, ProgressionRules.levelFor(skilled(110000, 10, 40)));
    }

    @Test
    void losingMoneyKeepsTheTraderBeginner() {
        assertEquals(UserLevel.DEBUTANT, ProgressionRules.levelFor(skilled(99000, 20, 60)));
    }

    @Test
    void levelDropsWhenBalanceFalls() {
        Stats advanced = skilled(200000, 8, 16);
        assertEquals(UserLevel.AVANCE, ProgressionRules.levelFor(advanced));
        Stats after = ProgressionRules.apply(advanced, new GameOutcome(140000, List.of(-0.2), 0, 0.3));
        assertEquals(UserLevel.INTERMEDIAIRE, ProgressionRules.levelFor(after));
    }

    @Test
    void applyAccumulatesStatsAndReplacesBalance() {
        Stats s = ProgressionRules.apply(Stats.fresh(), new GameOutcome(112045.6, List.of(0.1, -0.02, 0.05), 3, -0.06));
        assertEquals(112045.6, s.balance());
        assertEquals(1, s.games());
        assertEquals(3, s.trades());
        assertEquals(2, s.wins());
        assertEquals(0.06, s.sumMaxDrawdown(), 1e-9);
    }

    @Test
    void bankruptBelowThreshold() {
        assertTrue(ProgressionRules.bankrupt(new Stats(999, 5, 5, 0, 0, 1, 0, 1)));
        assertFalse(ProgressionRules.bankrupt(new Stats(1000, 5, 5, 0, 0, 1, 0, 1)));
    }

    @Test
    void difficultyGrowsWithLevel() {
        var easy = ProgressionRules.difficulty(UserLevel.DEBUTANT);
        var pro = ProgressionRules.difficulty(UserLevel.EXPERT);
        assertEquals(GuideMode.DETAILED, easy.guide());
        assertEquals(CoachMode.ALWAYS, easy.coach());
        assertFalse(easy.crashForced());
        assertEquals(GuideMode.NONE, pro.guide());
        assertEquals(CoachMode.HIDDEN, pro.coach());
        assertTrue(pro.crashForced());
        assertTrue(pro.feeRate() > easy.feeRate());
        double previous = -1;
        for (UserLevel l : UserLevel.values()) {
            var d = ProgressionRules.difficulty(l);
            assertTrue(d.feeRate() > previous);
            previous = d.feeRate();
        }
    }

    @Test
    void goalsDescribeTheNextLevel() {
        var next = ProgressionRules.nextRequirement(UserLevel.DEBUTANT);
        assertEquals(UserLevel.INTERMEDIAIRE, next.level());
        var goals = ProgressionRules.goals(Stats.fresh(), next);
        assertEquals(4, goals.size());
        assertEquals(120_000.0, goals.get(2).target());
        assertNull(ProgressionRules.nextRequirement(UserLevel.EXPERT));
    }
}
