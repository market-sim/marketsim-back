package tn.esprit.marketsim.agent.ticker;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.ticker.MarketTickerService.Quote;
import tn.esprit.marketsim.agent.ticker.MarketTickerService.Ticker;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.market.AssetRepository;
import tn.esprit.marketsim.market.MarketPriceEngine;

/**
 * Cotations mondiales pour le bandeau defilant (actions, indices, devises, or, cryptos).
 * <p>
 * Pour les instruments qui sont aussi des actifs tradables (AAPL, BTC, OR...), le bandeau affiche le prix du moteur
 * de prix du module 5 ({@code Asset.price}, ancre sur ce meme cours reel) : un seul prix par actif sur tout l'ecran.
 */
@RestController
@RequestMapping("/api/agents/ticker")
@RequiredArgsConstructor
public class MarketTickerController {

    private final MarketTickerService tickerService;
    private final AssetRepository assets;

    @GetMapping
    public Ticker ticker() {
        Ticker t = tickerService.ticker();
        Map<String, Asset> bySymbol = new HashMap<>();
        for (Asset a : assets.findAll()) {
            if (a.getSymbol() != null) bySymbol.put(a.getSymbol().toUpperCase(Locale.ROOT), a);
        }
        Map<String, String> assetOf = MarketPriceEngine.assetSymbolByTicker();
        List<Quote> quotes = t.quotes().stream().map(q -> {
            String key = q.symbol().toUpperCase(Locale.ROOT);
            Asset a = bySymbol.get(assetOf.getOrDefault(key, key));
            if (a == null || a.getPrice() == null || a.getPrice().signum() <= 0) return q;
            double price = a.getPrice().doubleValue();
            double pct = a.getChangePct() == null ? 0 : a.getChangePct();
            double change = price - price / (1 + pct / 100);
            return new Quote(q.symbol(), q.name(), q.category(), price, Math.round(change * 10_000) / 10_000.0, pct,
                    q.currency());
        }).toList();
        return new Ticker(quotes, t.updatedAt(), t.stale());
    }
}
