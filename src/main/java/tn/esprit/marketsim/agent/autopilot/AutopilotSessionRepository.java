package tn.esprit.marketsim.agent.autopilot;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AutopilotSessionRepository extends JpaRepository<AutopilotSession, Long> {

    Optional<AutopilotSession> findFirstByAgentIdOrderByIdDesc(Long agentId);

    List<AutopilotSession> findByAgentIdAndStatusIn(Long agentId, List<AutopilotSession.Status> statuses);

    List<AutopilotSession> findByStatusAndNextRunAtBefore(AutopilotSession.Status status, LocalDateTime time);

    List<AutopilotSession> findByAgentOwnerIdAndStatusIn(Long ownerId, List<AutopilotSession.Status> statuses);

    List<AutopilotSession> findByAgentId(Long agentId);
}
