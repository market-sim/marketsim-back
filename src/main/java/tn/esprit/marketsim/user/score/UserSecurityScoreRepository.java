package tn.esprit.marketsim.user.score;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSecurityScoreRepository extends JpaRepository<UserSecurityScore, Long> {

    Optional<UserSecurityScore> findByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
