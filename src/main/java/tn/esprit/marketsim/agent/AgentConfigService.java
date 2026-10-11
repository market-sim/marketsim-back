package tn.esprit.marketsim.agent;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;

@Service
@RequiredArgsConstructor
public class AgentConfigService {

    private final AgentConfigRepository repository;

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
        return repository.save(agent);
    }

    public AgentConfig update(Long id, AgentConfig data, User user) {
        AgentConfig agent = findOne(id, user);
        agent.setName(data.getName());
        agent.setStrategy(data.getStrategy());
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

    public void delete(Long id, User user) {
        repository.delete(findOne(id, user));
    }
}
