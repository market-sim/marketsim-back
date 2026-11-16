package tn.esprit.marketsim.progression;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.Optional;

import tn.esprit.marketsim.trading.TradingProperties;
import tn.esprit.marketsim.trading.TradingUserRepository;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;

/** Portefeuille unique de test : un compte (users.virtual_balance) et son profil de jeu, sans base. */
final class WalletFixture {

    final TraderProfileRepository profiles = mock(TraderProfileRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final TradingUserRepository accounts = mock(TradingUserRepository.class);
    final WalletService wallet = new WalletService(profiles, users, accounts, new TradingProperties(), null, null);

    WalletFixture account(User u, TraderProfile p) {
        lenient().when(accounts.findById(u.getId())).thenReturn(Optional.of(u));
        lenient().when(accounts.findByIdForUpdate(u.getId())).thenReturn(Optional.of(u));
        lenient().when(users.findById(u.getId())).thenReturn(Optional.of(u));
        if (p != null) lenient().when(profiles.findByUserId(u.getId())).thenReturn(Optional.of(p));
        lenient().when(profiles.save(any())).thenAnswer(i -> i.getArgument(0));
        return this;
    }

    static BigDecimal usd(String v) {
        return new BigDecimal(v);
    }
}
