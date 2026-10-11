package tn.esprit.marketsim.stress;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/stress/scenarios")
@RequiredArgsConstructor
public class StressScenarioController {

    private final StressScenarioService service;

    @GetMapping
    public List<StressScenario> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public StressScenario get(@PathVariable Long id) {
        return service.findOne(id);
    }

    @PostMapping
    public StressScenario create(@Valid @RequestBody StressScenario scenario) {
        return service.create(scenario);
    }

    @PutMapping("/{id}")
    public StressScenario update(@PathVariable Long id, @Valid @RequestBody StressScenario scenario) {
        return service.update(id, scenario);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
