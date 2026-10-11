package tn.esprit.marketsim.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentConfigRepository extends JpaRepository<AgentConfig, Long> {
    List<AgentConfig> findByOwnerId(Long ownerId);

    Optional<AgentConfig> findByIdAndOwnerId(Long id, Long ownerId);
}
