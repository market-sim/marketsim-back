package tn.esprit.marketsim.agent;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.autopilot.AutopilotAssetRepository;
import tn.esprit.marketsim.agent.autopilot.AutopilotEventRepository;
import tn.esprit.marketsim.agent.autopilot.AutopilotSessionRepository;
import tn.esprit.marketsim.agent.decision.AgentDecisionRepository;
import tn.esprit.marketsim.agent.montecarlo.MonteCarloRunRepository;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;

@Service
@RequiredArgsConstructor
public class AgentConfigService {

    private final AgentConfigRepository repository;
    private final AgentDecisionRepository decisionRepository;
    private final MonteCarloRunRepository monteCarloRunRepository;
    private final AutopilotSessionRepository autopilotSessionRepository;
    private final AutopilotAssetRepository autopilotAssetRepository;
    private final AutopilotEventRepository autopilotEventRepository;

    public List<AgentConfig> findMine(User user) {
        return repository.findByOwnerId(user.getId());
    }

    public AgentConfig findOne(Long id, User user) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotFoundException("Agent introuvable"));
    }

    public AgentConfig create(AgentConfig agent, User user) {
        agent.setId(null);
        agent.setOwner(user);
        agent.setQTable(null);
        if (agent.getStrategy() == null) {
            agent.setStrategy(AgentStrategy.MOMENTUM);
        }
        return repository.save(agent);
    }

    public AgentConfig update(Long id, AgentConfig data, User user) {
        AgentConfig agent = findOne(id, user);
        agent.setName(data.getName());
        if (data.getStrategy() != null && data.getStrategy() != agent.getStrategy()) {
            agent.setStrategy(data.getStrategy());
        }
        agent.setRiskLevel(data.getRiskLevel());
        agent.setAllowedAssets(data.getAllowedAssets());
        agent.setMaxPositionSize(data.getMaxPositionSize());
        return repository.save(agent);
    }

    /** Active / met en pause l'agent. */
    public AgentConfig toggle(Long id, User user) {
        AgentConfig agent = findOne(id, user);
        agent.setActive(!agent.isActive());
        return repository.save(agent);
    }

    @Transactional
    public void delete(Long id, User user) {
        AgentConfig agent = findOne(id, user);
        autopilotSessionRepository.findByAgentId(agent.getId()).forEach(session -> {
            autopilotEventRepository.deleteBySessionId(session.getId());
            autopilotAssetRepository.deleteBySessionId(session.getId());
            autopilotSessionRepository.delete(session);
        });
        decisionRepository.deleteByAgentId(agent.getId());
        monteCarloRunRepository.deleteByAgentId(agent.getId());
        repository.delete(agent);
    }
}
