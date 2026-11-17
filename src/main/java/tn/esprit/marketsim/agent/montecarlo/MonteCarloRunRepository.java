package tn.esprit.marketsim.agent.montecarlo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MonteCarloRunRepository extends JpaRepository<MonteCarloRun, Long> {

    List<MonteCarloRun> findTop20ByAgentIdOrderByCreatedAtDescIdDesc(Long agentId);

    void deleteByAgentId(Long agentId);
}
