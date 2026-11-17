package tn.esprit.marketsim.agent.realdata;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.ImportRequest;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.SeriesInfo;

/**
 * Donnees de marche reelles. Le symbole est un parametre de requete (et non du chemin) car il peut
 * contenir une barre oblique : {@code EUR/USD}, {@code BTC/USD}.
 */
@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketData;
    private final WalkForwardService walkForward;

    /** Historique reel (telecharge au premier appel, puis garde en base). */
    @GetMapping("/series")
    public SeriesInfo series(@RequestParam String symbol) {
        return walkForward.seriesInfo(marketData.series(symbol));
    }

    /** Retelecharge l'historique depuis Yahoo Finance. */
    @PostMapping("/refresh")
    public SeriesInfo refresh(@RequestParam String symbol) {
        return walkForward.seriesInfo(marketData.refresh(symbol));
    }

    /** Remplace l'historique par un export CSV. Reserve a l'administrateur : le jeu de donnees est partage. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/import")
    public SeriesInfo importCsv(@Valid @RequestBody ImportRequest req) {
        return walkForward.seriesInfo(marketData.importCsv(req.symbol(), req.csv()));
    }
}
