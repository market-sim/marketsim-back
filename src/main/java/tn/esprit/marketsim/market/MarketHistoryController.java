package tn.esprit.marketsim.market;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Module 5 — API des cours historiques réels des instruments de marché.
 *
 * <p>GET  /api/market/history?symbol=AAPL&amp;period=1M → consultation (authentifié)
 * <p>POST /api/market/history/refresh?symbol=AAPL       → actualisation depuis Yahoo Finance (utilisateur connecté)
 */
@RestController
@RequestMapping("/api/market/history")
@RequiredArgsConstructor
public class MarketHistoryController {

    private final MarketHistoryService historyService;

    /**
     * Récupère l'historique réel d'un instrument financier avec statut de fraîcheur et période filtrée.
     *
     * @param symbol symbole de l'actif (ex. AAPL, BTC/USD, WTI)
     * @param period période temporelle optionnelle (1M, 3M, 6M, 1Y, ALL)
     */
    @GetMapping
    public MarketHistoryDto get(@RequestParam String symbol,
                                @RequestParam(required = false, defaultValue = "ALL") String period) {
        return historyService.getHistory(symbol, period);
    }

    /**
     * Force l'actualisation depuis le fournisseur de cotations (tout utilisateur connecté).
     */
    @PostMapping("/refresh")
    @PreAuthorize("isAuthenticated()")
    public MarketHistoryDto refresh(@RequestParam String symbol) {
        return historyService.refresh(symbol);
    }
}
