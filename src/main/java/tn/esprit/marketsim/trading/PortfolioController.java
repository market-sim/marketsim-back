package tn.esprit.marketsim.trading;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.trading.TradingDtos.EquityPoint;
import tn.esprit.marketsim.trading.TradingDtos.PortfolioResponse;
import tn.esprit.marketsim.user.User;

/** Module 2 : portefeuille de l'utilisateur connecte. */
@RestController
@RequestMapping("/api/trading/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService service;

    @GetMapping
    public PortfolioResponse portfolio(@AuthenticationPrincipal User user) {
        return service.portfolio(user.getId());
    }

    /** Courbe d'equity : un point a chaque execution / cloture. */
    @GetMapping("/history")
    public List<EquityPoint> history(@AuthenticationPrincipal User user) {
        return service.history(user.getId());
    }
}
