package tn.esprit.marketsim.market;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Module 5 : actif financier cote (action, devise, matiere premiere, ...). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Asset {

    public enum Type { STOCK, BOND, COMMODITY, FOREX, CRYPTO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String symbol;

    @NotBlank
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    private Type type;

    @NotNull
    @Column(precision = 19, scale = 6)
    private BigDecimal price;

    /** Variation sur 24h en %. */
    private Double changePct;

    /** Fabrique utilisee par DataInitializer (pas de constructeur : Jackson l'utiliserait pour le JSON). */
    public static Asset of(String symbol, String name, Type type, String price, double changePct) {
        Asset a = new Asset();
        a.symbol = symbol;
        a.name = name;
        a.type = type;
        a.price = new BigDecimal(price);
        a.changePct = changePct;
        return a;
    }
}
