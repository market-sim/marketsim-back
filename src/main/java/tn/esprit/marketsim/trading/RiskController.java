package tn.esprit.marketsim.trading;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.trading.TradingDtos.PortfolioRiskResponse;
import tn.esprit.marketsim.trading.TradingDtos.RiskAssessRequest;
import tn.esprit.marketsim.trading.TradingDtos.RiskAssessment;
import tn.esprit.marketsim.user.User;

/** Module 2 : gestion du risque. */
@RestController
@RequestMapping("/api/trading/risk")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService service;

    /** Simulation a blanc d'un ordre (rien n'est cree) : score, niveau, controles et metriques. */
    @PostMapping("/assess")
    public RiskAssessment assess(@Valid @RequestBody RiskAssessRequest request, @AuthenticationPrincipal User user) {
        return service.assess(user.getId(), request);
    }

    /** VaR 95/99 %, CVaR 95 %, expositions, concentration (HHI), diversification, drawdown. */
    @GetMapping("/portfolio")
    public PortfolioRiskResponse portfolio(@AuthenticationPrincipal User user) {
        return service.portfolioRisk(user.getId());
    }
}
