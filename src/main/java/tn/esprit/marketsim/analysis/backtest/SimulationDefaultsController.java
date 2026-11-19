package tn.esprit.marketsim.analysis.backtest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Module 3 : parametres par defaut des simulations, en lecture pour tout utilisateur connecte (les formulaires
 * Backtest et Comparaison s'ouvrent preremplis avec). La modification est reservee a l'administrateur :
 * {@code PUT /api/analysis/admin/defaults}.
 *
 * <pre>
 *   GET /api/analysis/defaults     capital, frais, glissement, stop-loss, take-profit, part engagee, taux sans risque
 * </pre>
 */
@RestController
@RequestMapping("/api/analysis/defaults")
@RequiredArgsConstructor
public class SimulationDefaultsController {

    private final SimulationDefaultsService service;

    @GetMapping
    public BacktestSettings get() {
        return service.current();
    }
}
