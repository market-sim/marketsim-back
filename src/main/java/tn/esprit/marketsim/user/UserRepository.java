package tn.esprit.marketsim.user;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    long countByEnabled(boolean enabled);

    long countByRole(Role role);

    long countByRoleAndEnabled(Role role, boolean enabled);

    long countByLevel(UserLevel level);

    long countByLockedUntilAfter(LocalDateTime now);

    long countByCreatedAtGreaterThanEqual(LocalDateTime since);
}
