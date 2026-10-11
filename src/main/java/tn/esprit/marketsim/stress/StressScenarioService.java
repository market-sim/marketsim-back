package tn.esprit.marketsim.stress;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;

@Service
@RequiredArgsConstructor
public class StressScenarioService {

    private final StressScenarioRepository repository;

    public List<StressScenario> findAll() {
        return repository.findAll();
    }

    public StressScenario findOne(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Scenario introuvable"));
    }

    public StressScenario create(StressScenario scenario) {
        scenario.setId(null);
        return repository.save(scenario);
    }

    public StressScenario update(Long id, StressScenario data) {
        StressScenario scenario = findOne(id);
        scenario.setName(data.getName());
        scenario.setDescription(data.getDescription());
        scenario.setSeverity(data.getSeverity());
        scenario.setGdpShock(data.getGdpShock());
        scenario.setRateShock(data.getRateShock());
        scenario.setCreditShock(data.getCreditShock());
        return repository.save(scenario);
    }

    public void delete(Long id) {
        repository.delete(findOne(id));
    }
}
