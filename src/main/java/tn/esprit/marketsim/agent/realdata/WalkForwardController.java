package tn.esprit.marketsim.agent.realdata;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfigService;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.WalkForwardRequest;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.WalkForwardResult;
import tn.esprit.marketsim.user.User;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class WalkForwardController {

    private final AgentConfigService agentService;
    private final WalkForwardService walkForwardService;

    /** Backtest walk-forward sur historique reel : entrainement sur le passe, test sur le futur inconnu. */
    @PostMapping("/{id}/walkforward")
    public WalkForwardResult walkForward(@PathVariable Long id, @Valid @RequestBody WalkForwardRequest req,
                                         @AuthenticationPrincipal User user) {
        return walkForwardService.run(agentService.findOne(id, user), user, req);
    }
}
