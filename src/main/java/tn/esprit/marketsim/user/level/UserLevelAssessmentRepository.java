package tn.esprit.marketsim.user.level;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLevelAssessmentRepository extends JpaRepository<UserLevelAssessment, Long> {

    Optional<UserLevelAssessment> findByUserId(Long userId);
}
