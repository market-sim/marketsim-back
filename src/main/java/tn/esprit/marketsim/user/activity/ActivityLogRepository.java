package tn.esprit.marketsim.user.activity;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long>, JpaSpecificationExecutor<ActivityLog> {

    List<ActivityLog> findByActionAndActorIdAndOccurredAtBeforeOrderByOccurredAtDesc(
            ActivityAction action, Long actorId, Instant before, Pageable pageable);

    List<ActivityLog> findByActionAndActorIdIsNullAndOccurredAtBeforeOrderByOccurredAtDesc(
            ActivityAction action, Instant before, Pageable pageable);
}
