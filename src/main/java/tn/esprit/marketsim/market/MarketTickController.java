package tn.esprit.marketsim.market;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Module 5 : flux de cotations (historique compact enregistre par le moteur de prix). */
@RestController
@RequestMapping("/api/market/ticks")
@RequiredArgsConstructor
public class MarketTickController {

    private final AssetPriceTickRepository ticks;

    public record TickDto(Instant at, double price, boolean anchored) {
    }

    /** {@code GET /api/market/ticks?symbol=AAPL&hours=24} (1 a 168 heures). */
    @GetMapping
    public List<TickDto> ticks(@RequestParam String symbol, @RequestParam(defaultValue = "24") int hours) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("Symbole obligatoire");
        int h = Math.max(1, Math.min(168, hours));
        return ticks.findBySymbolAndAtAfterOrderByAtAsc(symbol.trim(), Instant.now().minus(h, ChronoUnit.HOURS))
                .stream()
                .map(t -> new TickDto(t.getAt(), t.getPrice().doubleValue(), Boolean.TRUE.equals(t.getAnchored())))
                .toList();
    }
}
