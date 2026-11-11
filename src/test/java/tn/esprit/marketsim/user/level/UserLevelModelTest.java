package tn.esprit.marketsim.user.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.user.UserLevel;

class UserLevelModelTest {

    @Test
    void learnedScoreSeparatesQuietAndActiveProfiles() {
        UserLevelModel model = UserLevelModel.current();
        UserLevelFeatures quiet = features(0.05, 0, 0, 0, 0, 0, 0, 0.05, 3);
        UserLevelFeatures active = features(0.9, 0.8, 0.9, 0.85, 0.8, 0.8, 0.8, 0.8, 30);

        int low = model.score(quiet);
        int high = model.score(active);
        assertTrue(low >= 0 && low <= 100);
        assertTrue(high >= 0 && high <= 100);
        assertTrue(high > low);
        assertEquals(low, model.score(quiet));
        assertEquals(UserLevelModel.VERSION, model.version());
        assertEquals(UserLevel.DEBUTANT, UserLevelModel.levelForScore(24));
        assertEquals(UserLevel.INTERMEDIAIRE, UserLevelModel.levelForScore(25));
        assertEquals(UserLevel.AVANCE, UserLevelModel.levelForScore(50));
        assertEquals(UserLevel.EXPERT, UserLevelModel.levelForScore(75));
        assertEquals(UserLevel.DEBUTANT, UserLevelModel.levelForScore(low));
        assertEquals(UserLevel.EXPERT, UserLevelModel.levelForScore(high));
    }

    private static UserLevelFeatures features(
            double login, double profile, double orders, double closed, double protection,
            double symbols, double balance, double age, int loginCount) {
        return new UserLevelFeatures(login, profile, orders, closed, protection, symbols, balance, age, loginCount);
    }
}
