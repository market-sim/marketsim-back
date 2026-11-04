package tn.esprit.marketsim.market;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Module 5 — flux d'actualités financières importées avec analyse de sentiment.
 *
 * GET  /api/market/news              → toutes les actualités (authentifié)
 * GET  /api/market/news?symbol=AAPL  → filtrées par symbole
 * POST /api/market/news              → importer un article (ADMIN)
 * DELETE /api/market/news/{id}       → supprimer (ADMIN)
 */
@RestController
@RequestMapping("/api/market/news")
@RequiredArgsConstructor
public class MarketNewsController {

    private final MarketNewsService service;

    /**
     * Retourne toutes les actualités (sans filtre) ou celles d'un symbole précis.
     *
     * @param symbol optionnel — ex : "AAPL", "BTC/USD"
     */
    @GetMapping
    public List<MarketNews> list(@RequestParam(required = false) String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            return service.findBySymbol(symbol);
        }
        return service.findAll();
    }

    /**
     * Importe un article et calcule automatiquement le sentiment.
     * Le client ne fournit jamais sentimentScore ni sentimentLabel.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public MarketNews create(@Valid @RequestBody MarketNewsRequest request) {
        return service.create(request);
    }

    /** Supprime un article importé. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
