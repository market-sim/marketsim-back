package tn.esprit.marketsim.agent.decision;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentDecisionRepository extends JpaRepository<AgentDecision, Long> {

    List<AgentDecision> findTop50ByAgentIdOrderByCreatedAtDescIdDesc(Long agentId);

    Optional<AgentDecision> findByIdAndAgentOwnerId(Long id, Long ownerId);

    /** Dernieres decisions de tous les agents d'un utilisateur (contexte de l'assistant). */
    List<AgentDecision> findTop8ByAgentOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId);

    void deleteByAgentId(Long agentId);
}
