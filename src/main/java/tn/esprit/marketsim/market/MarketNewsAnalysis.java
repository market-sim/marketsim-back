package tn.esprit.marketsim.market;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Historique append-only des analyses du modèle. Le titre et le symbole sont
 * conservés en instantané afin que l'historique subsiste si l'article est supprimé.
 */
@Entity
@Table(name = "market_news_analysis", indexes = {
        @Index(name = "idx_mna_symbol_published", columnList = "symbol,published_at"),
        @Index(name = "idx_mna_news_status_model", columnList = "news_id,status,model_name,model_version")
})
@Getter
@Setter
@NoArgsConstructor
public class MarketNewsAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identifiant d'origine conservé comme valeur d'audit, sans FK destructive. */
    @Column(name = "news_id", nullable = false)
    private Long newsId;

    @Column(nullable = false, length = 512)
    private String headline;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MarketSentimentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "sentiment_label", length = 16)
    private SentimentAnalyzer.Label sentimentLabel;

    @Column(name = "positive_probability", precision = 8, scale = 7)
    private BigDecimal positiveProbability;

    @Column(name = "negative_probability", precision = 8, scale = 7)
    private BigDecimal negativeProbability;

    @Column(name = "neutral_probability", precision = 8, scale = 7)
    private BigDecimal neutralProbability;

    @Column(name = "sentiment_score", precision = 8, scale = 7)
    private BigDecimal sentimentScore;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(name = "model_version", length = 96)
    private String modelVersion;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    /** Code stable et non sensible ; aucun détail interne d'exception n'est persisté. */
    @Column(name = "error_code", length = 80)
    private String errorCode;

    @PrePersist
    void setDefaultTimestamp() {
        if (analyzedAt == null) analyzedAt = Instant.now();
    }
}
