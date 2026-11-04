package tn.esprit.marketsim.market;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Module 5 — Actualité financière importée, liée à un actif (symbol).
 *
 * Le sentiment (score + label) est calculé automatiquement côté serveur
 * à l'import ; il n'est jamais fourni par le client.
 */
@Entity
@Table(name = "market_news")
@Getter
@Setter
@NoArgsConstructor
public class MarketNews {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Titre de l'article (headline). */
    @NotBlank
    @Column(nullable = false, length = 512)
    private String headline;

    /** Nom de la source (ex : "Reuters", "Bloomberg"). */
    @NotBlank
    @Column(nullable = false)
    private String source;

    /** URL de l'article. Peut être vide pour les imports manuels. */
    @Column(length = 1024)
    private String url;

    /**
     * Symbole de l'actif concerné (ex : "AAPL", "BTC/USD").
     * Correspond au symbol des Asset du module 5 mais sans contrainte FK
     * pour rester découplé.
     */
    @NotBlank
    @Column(nullable = false)
    private String symbol;

    /** Date de publication originale de l'article (ISO-8601 UTC). */
    @NotNull
    @Column(nullable = false)
    private Instant publishedAt;

    /**
     * Score de sentiment calculé automatiquement à l'import : −1.0 (très négatif)
     * à +1.0 (très positif). Jamais null.
     */
    @NotNull
    @Column(nullable = false)
    private Double sentimentScore;

    /** Label catégoriel dérivé du score : POSITIVE, NEUTRAL ou NEGATIVE. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SentimentAnalyzer.Label sentimentLabel;

    /** Horodatage de l'import dans MarketSim (UTC). */
    @Column(nullable = false)
    private Instant importedAt;

    /** Fabrique utilisée par DataInitializer et les tests. */
    public static MarketNews of(String headline, String source, String url,
                                String symbol, Instant publishedAt,
                                double sentimentScore, SentimentAnalyzer.Label sentimentLabel) {
        MarketNews n = new MarketNews();
        n.headline       = headline;
        n.source         = source;
        n.url            = url;
        n.symbol         = symbol;
        n.publishedAt    = publishedAt;
        n.sentimentScore = sentimentScore;
        n.sentimentLabel = sentimentLabel;
        n.importedAt     = Instant.now();
        return n;
    }
}
