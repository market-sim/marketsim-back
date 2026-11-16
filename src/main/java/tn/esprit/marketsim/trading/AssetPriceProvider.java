package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.market.Asset;

/**
 * Implementation par defaut : le prix stocke dans {@link Asset#getPrice()} (module 5).
 * <p>
 * Ce prix evolue : le moteur de prix du module 5 ({@code market.MarketPriceEngine}) le met a jour toutes les 3 s,
 * ancre sur les cours reels du bandeau quand ils sont disponibles. Le trading ne parle jamais au reseau : il lit
 * seulement la base. L'admin peut toujours forcer un prix via {@code PUT /api/market/assets/{id}}.
 */
@Component
@RequiredArgsConstructor
public class AssetPriceProvider implements PriceProvider {

    private final TradingAssetRepository assets;

    @Override
    public BigDecimal currentPrice(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbole obligatoire");
        }
        Asset asset = assets.findFirstBySymbolIgnoreCase(symbol.trim())
                .orElseThrow(() -> new IllegalArgumentException("Symbole inconnu : " + symbol));
        if (asset.getPrice() == null || asset.getPrice().signum() <= 0) {
            throw new IllegalArgumentException("Aucun prix disponible pour " + asset.getSymbol());
        }
        return asset.getPrice().setScale(Money.PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
