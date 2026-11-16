package tn.esprit.marketsim.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;

class LeaderboardServiceTest {

    private static TraderProfile profile(long userId, double balance, int games, int trades, int wins, UserLevel level) {
        TraderProfile p = TraderProfile.builder().userId(userId).level(level).bestLevel(level).build();
        p.setStats(new ProgressionRules.Stats(balance, games, trades, wins, wins * 0.08, (trades - wins) * 0.03, trades, games * 0.05));
        return p;
    }

    @Test
    void ranksBySkillAndHidesAdminsAndEmails() {
        User yasmine = User.builder().id(1L).firstName("Yasmine").lastName("Sabbagh").email("y@x.tn").role(Role.USER).enabled(true).virtualBalance(new java.math.BigDecimal("210000")).build();
        User omar = User.builder().id(2L).firstName("Omar").lastName("Trabelsi").email("o@x.tn").role(Role.USER).enabled(true).virtualBalance(new java.math.BigDecimal("90000")).build();
        User admin = User.builder().id(3L).firstName("Admin").lastName("M").email("a@x.tn").role(Role.ADMIN).enabled(true).build();
        UserRepository users = mock(UserRepository.class);
        when(users.findAll()).thenReturn(List.of(yasmine, omar, admin));
        TraderProfileRepository profiles = mock(TraderProfileRepository.class);
        when(profiles.findAll()).thenReturn(List.of(
                profile(1, 2100, 9, 20, 13, UserLevel.AVANCE),
                profile(2, 900, 4, 6, 1, UserLevel.DEBUTANT),
                profile(3, 5000, 20, 50, 40, UserLevel.EXPERT)));
        TraderGameRepository games = mock(TraderGameRepository.class);
        when(games.findTop100ByUserIdOrderByPlayedAtDesc(any())).thenReturn(List.of());

        var board = new LeaderboardService(profiles, games, users, new WalletFixture().wallet).leaderboard(omar, 50);
        assertEquals(2, board.traders(), "l'admin n'est pas classe");
        assertEquals("Yasmine S.", board.top().get(0).displayName());
        assertEquals(1, board.top().get(0).rank());
        assertEquals(2, board.me().rank());
        assertTrue(board.me().me());
        assertTrue(board.top().get(0).badges().stream().anyMatch(b -> b.id().equals("DOUBLED")));
        assertTrue(board.top().get(0).badges().stream().anyMatch(b -> b.id().equals("DISCIPLINED")));
        assertFalse(board.top().get(1).badges().stream().anyMatch(b -> b.id().equals("DOUBLED")));
    }
}
