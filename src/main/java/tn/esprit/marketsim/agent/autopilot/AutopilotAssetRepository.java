package tn.esprit.marketsim.agent.autopilot;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AutopilotAssetRepository extends JpaRepository<AutopilotAsset, Long> {

    List<AutopilotAsset> findBySessionIdOrderByIdAsc(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
