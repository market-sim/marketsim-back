package tn.esprit.marketsim.market;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Module 5 : un point du flux de cotations (historique compact, purge automatique). */
@Entity
@Table(name = "asset_price_tick", indexes = @Index(name = "idx_tick_symbol_at", columnList = "symbol, at"))
@Getter
@Setter
@NoArgsConstructor
public class AssetPriceTick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal price;

    @Column(nullable = false)
    private Instant at;

    /** true si le prix etait ancre sur un cours reel (Yahoo / CoinGecko), false si simule. */
    private Boolean anchored;

    public static AssetPriceTick of(String symbol, BigDecimal price, Instant at, boolean anchored) {
        AssetPriceTick t = new AssetPriceTick();
        t.symbol = symbol;
        t.price = price;
        t.at = at;
        t.anchored = anchored;
        return t;
    }
}
