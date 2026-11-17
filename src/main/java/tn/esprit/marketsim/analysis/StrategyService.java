package tn.esprit.marketsim.analysis;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;

@Service
@RequiredArgsConstructor
public class StrategyService {

    private final StrategyRepository repository;

    public List<Strategy> findMine(User user) {
        return repository.findByOwnerId(user.getId());
    }

    public Strategy findOne(Long id, User user) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotFoundException("Strategie introuvable"));
    }

    public Strategy create(Strategy strategy, User user) {
        strategy.setId(null);
        strategy.setOwner(user);
        return repository.save(strategy);
    }

    public Strategy update(Long id, Strategy data, User user) {
        Strategy strategy = findOne(id, user);
        strategy.setName(data.getName());
        strategy.setDescription(data.getDescription());
        strategy.setIndicators(data.getIndicators());
        if (data.getStatus() != null) {
            strategy.setStatus(data.getStatus());
        }
        return repository.save(strategy);
    }

    public void delete(Long id, User user) {
        repository.delete(findOne(id, user));
    }
}
