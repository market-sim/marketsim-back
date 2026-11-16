package tn.esprit.marketsim.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;

class WalletServiceTest {

    private static User trader(String balance) {
        return User.builder().id(5L).role(Role.USER).enabled(true).level(UserLevel.INTERMEDIAIRE)
                .virtualBalance(new BigDecimal(balance)).build();
    }

    private static TraderProfile profile() {
        TraderProfile p = TraderProfile.builder().userId(5L).level(UserLevel.INTERMEDIAIRE).build();
        p.setStats(new ProgressionRules.Stats(1500, 4, 8, 5, 0.5, 0.1, 8, 0.2)); // ancienne copie : ignoree
        return p;
    }

    @Test
    void lePiloteEmprunteEtRendSurLeSoldeUnique() {
        User u = trader("150000.00");
        TraderProfile p = profile();
        WalletFixture f = new WalletFixture().account(u, p);
        WalletService wallet = f.wallet;

        // la copie du jeu suit le solde unique, pas l'ancienne valeur
        assertEquals(150000, wallet.balance(5L), 1e-9);
        assertEquals(50000, wallet.debit(5L, 100000), 1e-9);
        assertEquals(0, new BigDecimal("50000.00").compareTo(u.getVirtualBalance()), "le debit est visible dans users.virtual_balance");
        assertEquals(50000, p.getBalance(), 1e-9);
        assertEquals(UserLevel.INTERMEDIAIRE, p.getLevel(), "l'argent prete au pilote ne fait pas perdre le niveau");
        assertThrows(ConflictException.class, () -> wallet.debit(5L, 60000));
        assertEquals(0, new BigDecimal("50000.00").compareTo(u.getVirtualBalance()), "un debit refuse ne touche a rien");
        assertEquals(160000, wallet.credit(5L, 110000), 1e-9);
        wallet.debit(5L, 160000);
        wallet.credit(5L, 70000);
        assertEquals(UserLevel.DEBUTANT, p.getLevel(), "le pilote a perdu : sous le capital de depart, retour Debutant");
    }

    @Test
    void aucunArgentCreeNiPerduEntreLesDeuxSystemes() {
        User u = trader("100000.00");
        WalletFixture f = new WalletFixture().account(u, profile());
        BigDecimal start = u.getVirtualBalance();
        java.util.Random r = new java.util.Random(3);
        BigDecimal expected = start;
        for (int i = 0; i < 500; i++) {
            double amount = Math.round(r.nextDouble() * 5000 * 100) / 100.0;
            if (r.nextBoolean()) {
                try {
                    f.wallet.debit(5L, amount);
                    expected = expected.subtract(BigDecimal.valueOf(amount));
                } catch (ConflictException e) {
                    // solde insuffisant : rien ne bouge
                }
            } else {
                f.wallet.credit(5L, amount);
                expected = expected.add(BigDecimal.valueOf(amount));
            }
            assertEquals(0, expected.setScale(2).compareTo(u.getVirtualBalance()));
            assertEquals(u.getVirtualBalance().doubleValue(), f.wallet.balance(5L), 1e-9);
        }
    }

    @Test
    void unResultatDePartieNeRendJamaisLeSoldeNegatif() {
        User u = trader("300.00");
        WalletFixture f = new WalletFixture().account(u, profile());
        WalletService.Movement m = f.wallet.apply(5L, new BigDecimal("-1000"), WalletService.Reason.GAME_RESULT, true, null);
        assertEquals(0, BigDecimal.ZERO.compareTo(m.after()));
    }

    @Test
    void lAdminNAPasDePortefeuille() {
        User admin = User.builder().id(5L).role(Role.ADMIN).enabled(true).virtualBalance(new BigDecimal("100000")).build();
        WalletFixture f = new WalletFixture().account(admin, profile());
        assertThrows(ConflictException.class, () -> f.wallet.credit(5L, 10));
    }
}
