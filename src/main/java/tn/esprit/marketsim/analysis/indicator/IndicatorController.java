package tn.esprit.marketsim.analysis.indicator;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.indicator.IndicatorDtos.IndicatorResponse;

/**
 * Module 3 : indicateurs techniques d'un actif. Le symbole est un parametre de requete (et non du chemin)
 * car il peut contenir une barre oblique : {@code EUR/USD}.
 *
 * <p>Exemple : {@code GET /api/analysis/indicators?symbol=AAPL&sma=20,50&rsi=14&from=2024-01-01}.
 * Sans aucun indicateur demande : SMA(20,50), EMA(20), RSI(14), MACD(12,26,9) et Bollinger(20,2).
 */
@RestController
@RequestMapping("/api/analysis/indicators")
@RequiredArgsConstructor
public class IndicatorController {

    private final IndicatorService service;

    @GetMapping
    public IndicatorResponse indicators(
            @RequestParam String symbol,
            @RequestParam(required = false) String sma,
            @RequestParam(required = false) String ema,
            @RequestParam(required = false) String rsi,
            @RequestParam(required = false) String macd,
            @RequestParam(required = false) String bollinger,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.indicators(symbol, sma, ema, rsi, macd, bollinger, from, to);
    }
}
