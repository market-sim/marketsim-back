package tn.esprit.marketsim.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.notification.NotificationService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;

class AdminWalletServiceTest {

    TraderProfileRepository profiles = mock(TraderProfileRepository.class);
    UserRepository users = mock(UserRepository.class);
    ActivityLogService log = mock(ActivityLogService.class);
    NotificationService notifications = mock(NotificationService.class);
    TraderProfile profile;
    AdminWalletService service;
    User admin = User.builder().id(1L).role(Role.ADMIN).enabled(true).build();

    @BeforeEach
    void setUp() {
        profile = TraderProfile.builder().userId(7L).level(UserLevel.INTERMEDIAIRE).bestLevel(UserLevel.INTERMEDIAIRE).build();
        profile.setStats(new ProgressionRules.Stats(1500, 4, 12, 8, 0.6, 0.1, 11, 0.2));
        when(profiles.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(profiles.save(any())).thenAnswer(i -> i.getArgument(0));
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).role(Role.USER).enabled(true).level(UserLevel.INTERMEDIAIRE).build()));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        service = new AdminWalletService(new WalletService(profiles, users), users, log, notifications);
    }

    @Test
    void setsBalanceRecomputesLevelLogsAndNotifies() {
        WalletService.Adjustment a = service.setBalance(admin, 7L, new AdminWalletService.SetBalanceRequest(800.456, "correction bug"));
        assertEquals(1500, a.before(), 1e-9);
        assertEquals(800.46, a.after(), 1e-9);
        assertEquals(UserLevel.DEBUTANT, a.levelAfter(), "sous 1 000 $ : retour Debutant");
        verify(log).recordAfterCommit(eq(1L), eq(7L), eq(ActivityAction.ADMIN_SET_BALANCE), eq("OK"), any());
        verify(notifications).notify(eq(7L), any(), anyString(), anyString(), eq("/progression"));
    }

    @Test
    void reasonAndBoundsAreMandatory() {
        assertThrows(IllegalArgumentException.class, () -> service.setBalance(admin, 7L, new AdminWalletService.SetBalanceRequest(500.0, " ")));
        assertThrows(IllegalArgumentException.class, () -> service.setBalance(admin, 7L, new AdminWalletService.SetBalanceRequest(-1.0, "test")));
        assertThrows(IllegalArgumentException.class, () -> service.setBalance(admin, 7L, new AdminWalletService.SetBalanceRequest(2_000_000.0, "test")));
        assertThrows(IllegalArgumentException.class, () -> service.setBalance(admin, 7L, new AdminWalletService.SetBalanceRequest(null, "test")));
        assertEquals(1500, profile.getBalance(), 1e-9);
        verify(log, never()).recordAfterCommit(any(), any(), any(), any(), any());
    }

    @Test
    void adminAccountsHaveNoWallet() {
        assertThrows(ConflictException.class, () -> service.setBalance(admin, 1L, new AdminWalletService.SetBalanceRequest(500.0, "test")));
    }
}
