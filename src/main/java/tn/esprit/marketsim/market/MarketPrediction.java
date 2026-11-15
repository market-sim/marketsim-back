package tn.esprit.marketsim.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
 * Module 5 — Prédiction de tendance générée par le modèle interne
 * (linear-momentum-v1) et stockée pour suivi de fiabilité.
 *
 * <p>Chaque prédiction est créée avec outcome = null (en attente).
 * Quand l'horizon est atteint, la tâche planifiée {@code resolveExpired}
 * compare le prix actuel au prix de référence et marque la prédiction
 * CORRECT ou INCORRECT.
 */
@Entity
@Table(name = "market_prediction")
@Getter
@Setter
@NoArgsConstructor
public class MarketPrediction {

    public enum Direction { UP, DOWN, FLAT }

    public enum Outcome { CORRECT, INCORRECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Symbole de l'actif concerné (ex : "AAPL", "BTC/USD"). */
    @NotBlank
    @Column(nullable = false)
    private String symbol;

    /** Direction prédite pour l'horizon. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    /** Confiance du modèle : 0.0 (incertain) à 1.0 (très confiant). */
    @NotNull
    @Column(nullable = false)
    private Double confidence;

    /** Prix de l'actif au moment de la génération. */
    @NotNull
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal currentPrice;

    /** Prix cible projeté à la fin de l'horizon. Toujours positif. */
    @NotNull
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal targetPrice;

    /** Nombre de jours de l'horizon de prédiction. */
    @NotNull
    @Column(nullable = false)
    private Integer horizonDays;

    /** Horodatage de la génération (UTC). */
    @NotNull
    @Column(nullable = false)
    private Instant generatedAt;

    /** Date des données utilisées pour la prédiction (UTC). */
    @NotNull
    @Column(nullable = false)
    private Instant dataAsOf;

    /** Nom du modèle ayant produit la prédiction (ex : "holt-linear-trend"). */
    @Column(length = 64)
    private String modelName;

    /** Version du modèle ayant produit la prédiction. */
    @NotBlank
    @Column(nullable = false)
    private String modelVersion;

    /** Source des données historiques utilisées (YAHOO ou CSV). */
    @Column(length = 16)
    private String dataSource;

    /** Empreinte SHA-256 du jeu de données historiques utilisé (reproductibilité). */
    @Column(length = 64)
    private String dataHash;

    /** Première date de la série historique utilisée (UTC). */
    private LocalDate dataFrom;

    /** Dernière date de la série historique utilisée = cutoff des données (UTC). */
    private LocalDate dataTo;

    /** Nombre de points historiques utilisés. */
    private Integer dataPoints;

    /** Métriques d'évaluation sur un jeu de test chronologique (peuvent être nulles). */
    private Double mae;
    private Double rmse;
    private Double mape;

    /** Nombre de points du jeu de test utilisé pour l'évaluation. */
    private Integer evaluationPoints;

    /** Statut de la prédiction : GENERATED (en attente de résolution) ou RESOLVED. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PredictionStatus status;

    /** Quantiles p10/p90 du prix à l'horizon (prévision probabiliste, bootstrap des résidus). */
    private Double fanP10;
    private Double fanP90;

    /** Série de quantiles par jour (JSON) pour le fan chart : [{"day":1,"p10":..,"p50":..,"p90":..},...]. */
    @Column(columnDefinition = "LONGTEXT")
    private String fanJson;

    /** Métriques des modèles candidats (JSON) : [{"name":"arima","mae":..,"rmse":..},...]. */
    @Column(columnDefinition = "LONGTEXT")
    private String candidateMetricsJson;

    /** Score de sentiment FinBERT utilisé comme variable exogène (peut être null). */
    private Double sentimentScore;

    /** Contribution du sentiment à la prédiction (XAI), en unités de prix. */
    private Double sentimentContribution;

    public enum PredictionStatus { GENERATED, RESOLVED }

    /**
     * Résultat de la prédiction une fois l'horizon écoulé.
     * null = en attente (pending), CORRECT ou INCORRECT.
     */
    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    /** Horodatage de la résolution (UTC). null tant que l'horizon n'est pas atteint. */
    private Instant resolvedAt;
}
