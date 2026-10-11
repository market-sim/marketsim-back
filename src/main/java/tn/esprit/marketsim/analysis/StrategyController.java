package tn.esprit.marketsim.analysis;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import tn.esprit.marketsim.user.User;

@RestController
@RequestMapping("/api/analysis/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService service;

    @GetMapping
    public List<Strategy> list(@AuthenticationPrincipal User user) {
        return service.findMine(user);
    }

    @GetMapping("/{id}")
    public Strategy get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.findOne(id, user);
    }

    @PostMapping
    public Strategy create(@Valid @RequestBody Strategy strategy, @AuthenticationPrincipal User user) {
        return service.create(strategy, user);
    }

    @PutMapping("/{id}")
    public Strategy update(@PathVariable Long id, @Valid @RequestBody Strategy strategy,
                           @AuthenticationPrincipal User user) {
        return service.update(id, strategy, user);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.delete(id, user);
    }
}
