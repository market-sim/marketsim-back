package tn.esprit.marketsim.agent.portfolio;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfigService;
import tn.esprit.marketsim.agent.portfolio.PortfolioService.PortfolioRequest;
import tn.esprit.marketsim.agent.portfolio.PortfolioService.PortfolioResult;
import tn.esprit.marketsim.user.User;

@RestController("agentPortfolioController")
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class PortfolioController {

    private final AgentConfigService agentService;
    private final PortfolioService portfolioService;

    @PostMapping("/{id}/portfolio")
    public PortfolioResult portfolio(@PathVariable Long id, @Valid @RequestBody PortfolioRequest req,
                                     @AuthenticationPrincipal User user) {
        return portfolioService.run(agentService.findOne(id, user), req);
    }
}
