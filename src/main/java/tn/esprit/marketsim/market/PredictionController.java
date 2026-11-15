package tn.esprit.marketsim.market;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Module 5 — API des prédictions de tendance.
 *
 * GET  /api/market/predictions                  → toutes les prédictions (authentifié)
 * GET  /api/market/predictions?symbol=AAPL       → filtrées par symbole
 * GET  /api/market/predictions/{id}              → une prédiction par id
 * GET  /api/market/predictions/{id}/evaluation   → métriques d'évaluation
 * GET  /api/market/predictions/models            → modèles disponibles
 * POST /api/market/predictions/generate?symbol=AAPL&horizon=5 → générer (ADMIN)
 */
@RestController
@RequestMapping("/api/market/predictions")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService service;

    @GetMapping
    public List<MarketPrediction> list(@RequestParam(required = false) String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            return service.findBySymbol(symbol);
        }
        return service.findAll();
    }

    @GetMapping("/models")
    public List<PredictionModelInfo> models() {
        return service.models();
    }

    @GetMapping("/{id}/evaluation")
    public PredictionEvaluation evaluation(@PathVariable Long id) {
        return service.evaluation(id);
    }

    @GetMapping("/backtest")
    public PredictionBacktest backtest(@RequestParam(required = false) String symbol) {
        return service.backtest(symbol);
    }

    @GetMapping("/{id}")
    public MarketPrediction get(@PathVariable Long id) {
        return service.findOne(id);
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public MarketPrediction generate(@RequestParam String symbol,
                                     @RequestParam(required = false) Integer horizon) {
        return service.generateFor(symbol, horizon);
    }
}