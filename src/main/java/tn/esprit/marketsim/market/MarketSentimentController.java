package tn.esprit.marketsim.market;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** API d'agrégation FinBERT du Module 5. */
@RestController
@RequestMapping("/api/market/sentiment")
@RequiredArgsConstructor
public class MarketSentimentController {

    private final FinancialSentimentService service;

    /** Agrégat des derniers résultats réussis par article sur les 30 derniers jours par défaut. */
    @GetMapping
    public SentimentSummaryResponse summary(@RequestParam String symbol,
                                            @RequestParam(required = false) Integer days) {
        return service.summary(symbol, days);
    }

    /** Historique journalier du sentiment sur la même fenêtre temporelle. */
    @GetMapping("/history")
    public SentimentHistoryResponse history(@RequestParam String symbol,
                                           @RequestParam(required = false) Integer days) {
        return service.history(symbol, days);
    }
}
