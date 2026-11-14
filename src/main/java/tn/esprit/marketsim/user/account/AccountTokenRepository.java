package tn.esprit.marketsim.user.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    Optional<AccountToken> findByTokenHash(String tokenHash);

    List<AccountToken> findByUserIdAndPurposeAndUsedAtIsNull(Long userId, AccountToken.Purpose purpose);

    List<AccountToken> findByUserId(Long userId);
}
