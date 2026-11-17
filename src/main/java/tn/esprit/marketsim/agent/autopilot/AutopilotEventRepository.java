package tn.esprit.marketsim.agent.autopilot;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AutopilotEventRepository extends JpaRepository<AutopilotEvent, Long> {

    List<AutopilotEvent> findTop60BySessionIdOrderByIdDesc(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
