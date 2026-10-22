package tn.esprit.marketsim.user.anomaly;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LoginAnomalyAssessmentRepository
        extends JpaRepository<LoginAnomalyAssessment, Long>, JpaSpecificationExecutor<LoginAnomalyAssessment> {

    Optional<LoginAnomalyAssessment> findByActivityLogId(Long activityLogId);

    List<LoginAnomalyAssessment> findByActorId(Long actorId);

    boolean existsByActorId(Long actorId);
}
