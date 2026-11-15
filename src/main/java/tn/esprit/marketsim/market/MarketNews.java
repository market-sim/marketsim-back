package tn.esprit.marketsim.market;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
 *
 * Supporte les fournisseurs externes réels (Yahoo Finance, Google News fallback, Finnhub)
 * ainsi que l'import manuel d'actualités.
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

    /** Nom de la source originale (ex : "Reuters", "Bloomberg", "CNBC"). */
    @NotBlank
    @Column(nullable = false)
    private String source;

    /** URL de l'article original. */
    @Column(length = 1024)
    private String url;

    /**
     * Symbole de l'actif concerné (ex : "AAPL", "BTC/USD").
     * Correspond au symbol des Asset du module 5.
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

    /** Fournisseur externe d'origine (ex : "YAHOO_FINANCE", "GOOGLE_NEWS", "FINNHUB", "MANUAL"). */
    @Column(length = 64)
    private String provider;

    /** Statut des données de l'article : REAL, CACHED, MANUAL. */
    @Column(length = 32)
    private String dataStatus;

    /**
     * Empreinte SHA-256 unique de l'article (basée sur l'URL normalisée, ou headline+symbol+publishedAt).
     * Garantit la déduplication au niveau base sans dépasser la limite de longueur de clé MySQL.
     */
    @Column(length = 64, unique = true)
    private String urlHash;

    @PrePersist
    public void ensureDefaults() {
        if (this.urlHash == null || this.urlHash.isBlank()) {
            this.urlHash = computeHash(this.url, this.headline, this.symbol, this.publishedAt);
        }
        if (this.provider == null || this.provider.isBlank()) {
            this.provider = "MANUAL";
        }
        if (this.dataStatus == null || this.dataStatus.isBlank()) {
            this.dataStatus = "MANUAL";
        }
        if (this.importedAt == null) {
            this.importedAt = Instant.now();
        }
    }

    /**
     * Calcule l'empreinte SHA-256 unique pour un article.
     * Si l'URL est renseignée, l'empreinte repose sur l'URL normalisée.
     * Sinon, elle se base sur le titre, le symbole et l'horodatage.
     */
    public static String computeHash(String url, String headline, String symbol, Instant publishedAt) {
        String raw;
        if (url != null && !url.trim().isEmpty()) {
            raw = url.trim();
        } else {
            raw = (headline != null ? headline.trim().toLowerCase() : "")
                + "|" + (symbol != null ? symbol.trim().toUpperCase() : "")
                + "|" + (publishedAt != null ? publishedAt.toString() : "");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible dans la JVM", e);
        }
    }

    /** Fabrique rétrocompatible utilisée par DataInitializer et les tests. */
    public static MarketNews of(String headline, String source, String url,
                                String symbol, Instant publishedAt,
                                double sentimentScore, SentimentAnalyzer.Label sentimentLabel) {
        MarketNews n = new MarketNews();
        n.headline       = headline;
        n.source         = source;
        n.url            = url;
        n.symbol         = symbol != null ? symbol.toUpperCase() : null;
        n.publishedAt    = publishedAt;
        n.sentimentScore = sentimentScore;
        n.sentimentLabel = sentimentLabel;
        n.provider       = "MANUAL";
        n.dataStatus     = "MANUAL";
        n.urlHash        = computeHash(url, headline, symbol, publishedAt);
        n.importedAt     = Instant.now();
        return n;
    }

    /** Fabrique pour les actualités importées depuis un fournisseur externe réel. */
    public static MarketNews fromProvider(String headline, String source, String url,
                                         String symbol, Instant publishedAt,
                                         double sentimentScore, SentimentAnalyzer.Label sentimentLabel,
                                         String provider, String dataStatus) {
        MarketNews n = of(headline, source, url, symbol, publishedAt, sentimentScore, sentimentLabel);
        n.provider   = provider != null ? provider : "MANUAL";
        n.dataStatus = dataStatus != null ? dataStatus : "REAL";
        return n;
    }
}
