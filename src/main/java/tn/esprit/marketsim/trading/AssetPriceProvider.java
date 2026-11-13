package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.market.Asset;

/**
 * Implementation par defaut : le prix stocke dans {@link Asset#getPrice()} (module 5).
 * <p>
 * Limite connue : le bandeau live ({@code MarketTickerService}) n'est pas utilise. Il couvre d'autres symboles
 * (« OR » au lieu de « GOLD »...), fait des appels HTTP bloquants (jusqu'a 8 s) et n'est rafraichi que toutes les
 * 60 s : l'appeler depuis une transaction d'ordre ou depuis le moteur (toutes les 2 s) serait fragile. Tant que le
 * module 5 ne met pas a jour {@code Asset.price}, les prix sont statiques (l'admin peut les modifier via
 * {@code PUT /api/market/assets/{id}}, ce qui declenche SL/TP/LIMIT au balayage suivant).
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
