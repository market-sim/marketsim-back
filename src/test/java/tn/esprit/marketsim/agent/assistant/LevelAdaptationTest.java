package tn.esprit.marketsim.agent.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.assistant.ChartAnalysisService.ChartRequest;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;

/** L'agent s'adapte au niveau du joueur : profil de risque impose et style des reponses d'Atlas. */
class LevelAdaptationTest {

    private static ChartRequest aggressive() {
        return new ChartRequest("aGVsbG8=", "image/png", null, null, "AGGRESSIVE", 2.0, 1000.0);
    }

    @Test
    void beginnerIsForcedToTheCautiousProfile() {
        User beginner = User.builder().role(Role.USER).level(UserLevel.DEBUTANT).build();
        ChartRequest r = ChartAnalysisService.forLevel(beginner, aggressive());
        assertEquals("CONSERVATIVE", r.riskProfile());
        assertEquals(0.5, r.riskPercent(), 1e-9);
    }

    @Test
    void higherLevelsAndAdminKeepTheirChoice() {
        ChartRequest req = aggressive();
        assertSame(req, ChartAnalysisService.forLevel(User.builder().role(Role.USER).level(UserLevel.AVANCE).build(), req));
        assertSame(req, ChartAnalysisService.forLevel(User.builder().role(Role.ADMIN).level(UserLevel.DEBUTANT).build(), req));
    }

    @Test
    void atlasStyleFollowsTheLevel() {
        assertTrue(AssistantContextBuilder.styleFor("DEBUTANT").contains("mots simples"));
        assertTrue(AssistantContextBuilder.styleFor("EXPERT").contains("aucune vulgarisation"));
        assertTrue(AssistantContextBuilder.styleFor("ADMIN").contains("administrateur"));
    }
}
