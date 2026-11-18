package tn.esprit.marketsim.analysis.fundamental;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.Detail;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.Overview;

/**
 * Module 3 : analyse fondamentale des actions (donnees des rapports annuels 10-K de la SEC).
 *
 * <pre>
 *   GET  /api/analysis/fundamentals                    actions et etat de leurs donnees (cache seulement)
 *   GET  /api/analysis/fundamentals/{symbol}           analyse complete (telecharge si besoin)
 *   POST /api/analysis/fundamentals/{symbol}/refresh   retelechargement force, reserve a l'administrateur
 * </pre>
 * Le symbole peut etre dans le chemin : seules les actions sont concernees, et leur symbole n'a pas de barre oblique.
 */
@RestController
@RequestMapping("/api/analysis/fundamentals")
@RequiredArgsConstructor
public class FundamentalsController {

    private final FundamentalsService service;

    @GetMapping
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/{symbol}")
    public Detail get(@PathVariable String symbol) {
        return service.get(symbol);
    }

    /** Le cache est partage par tous les utilisateurs : seul l'administrateur force son renouvellement. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{symbol}/refresh")
    public Detail refresh(@PathVariable String symbol) {
        return service.refresh(symbol);
    }
}
