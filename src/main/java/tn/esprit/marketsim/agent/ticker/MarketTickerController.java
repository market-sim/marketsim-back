package tn.esprit.marketsim.agent.ticker;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.ticker.MarketTickerService.Ticker;

/** Cotations mondiales en temps reel pour le bandeau defilant (actions, indices, devises, or, cryptos). */
@RestController
@RequestMapping("/api/agents/ticker")
@RequiredArgsConstructor
public class MarketTickerController {

    private final MarketTickerService tickerService;

    @GetMapping
    public Ticker ticker() {
        return tickerService.ticker();
    }
}
