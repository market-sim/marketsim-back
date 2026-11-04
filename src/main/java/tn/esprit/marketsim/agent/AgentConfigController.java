package tn.esprit.marketsim.agent;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentConfigController {

    private final AgentConfigService service;

    @GetMapping
    public List<AgentConfig> list(@AuthenticationPrincipal User user) {
        return service.findMine(user);
    }

    @GetMapping("/{id}")
    public AgentConfig get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.findOne(id, user);
    }

    @PostMapping
    public AgentConfig create(@Valid @RequestBody AgentConfig agent, @AuthenticationPrincipal User user) {
        return service.create(agent, user);
    }

    @PutMapping("/{id}")
    public AgentConfig update(@PathVariable Long id, @Valid @RequestBody AgentConfig agent,
                              @AuthenticationPrincipal User user) {
        return service.update(id, agent, user);
    }

    @PatchMapping("/{id}/toggle")
    public AgentConfig toggle(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.toggle(id, user);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.delete(id, user);
    }
}
