package tn.esprit.marketsim.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;

class WalletServiceTest {

    @Test
    void autopilotBorrowsAndReturnsMoney() {
        TraderProfileRepository repo = mock(TraderProfileRepository.class);
        TraderProfile p = TraderProfile.builder().userId(5L).level(UserLevel.INTERMEDIAIRE).build();
        p.setStats(new ProgressionRules.Stats(1500, 4, 8, 5, 0.5, 0.1, 8, 0.2));
        when(repo.findByUserId(5L)).thenReturn(Optional.of(p));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        UserRepository users = mock(UserRepository.class);
        WalletService wallet = new WalletService(repo, users);

        assertEquals(500, wallet.debit(5L, 1000), 1e-9);
        assertEquals(UserLevel.INTERMEDIAIRE, p.getLevel(), "l'argent prete au pilote ne fait pas perdre le niveau");
        assertThrows(ConflictException.class, () -> wallet.debit(5L, 600));
        assertEquals(1600, wallet.credit(5L, 1100), 1e-9);
        assertEquals(UserLevel.INTERMEDIAIRE, p.getLevel(), "le pilote a rendu l'argent avec un gain");
        wallet.debit(5L, 1600);
        wallet.credit(5L, 700);
        assertEquals(UserLevel.DEBUTANT, p.getLevel(), "le pilote a perdu : sous 1 000 $, retour Debutant");
    }
}
