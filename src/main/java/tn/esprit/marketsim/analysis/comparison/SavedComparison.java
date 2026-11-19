package tn.esprit.marketsim.analysis.comparison;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : comparaison de strategies enregistree dans l'historique.
 *
 * <p>Le resultat est calcule par le serveur au moment de l'enregistrement (jamais recu du front) et garde tel
 * quel en JSON. Il n'y a volontairement aucune cle etrangere vers {@code Strategy} : chaque strategie comparee
 * est copiee (nom, type, parametres), donc la comparaison reste lisible si une strategie est modifiee ou
 * supprimee ensuite. Cette entite n'est jamais renvoyee telle quelle au front : voir {@link ComparisonDtos}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class SavedComparison {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @Column(nullable = false, length = SavedComparisonService.NAME_MAX)
    private String name;

    @Column(nullable = false, length = 32)
    private String symbol;

    // Periode reellement comparee (premiere et derniere seance)
    @Column(nullable = false)
    private LocalDate fromDate;

    @Column(nullable = false)
    private LocalDate toDate;

    /** Date de coupure entrainement / test (null si non demandee). */
    private LocalDate splitDate;

    /** Critere de classement choisi a l'enregistrement. Colonne VARCHAR (et non ENUM MySQL), comme Strategy.type. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(32)")
    private ComparisonRanking rankBy;

    /** Rendement du buy & hold sur la periode (fraction), commun a toutes les strategies : affiche dans la liste. */
    private Double buyHoldReturn;

    /** Origine de l'historique utilise : YAHOO ou CSV. */
    @Column(length = 16)
    private String dataSource;

    /** Empreinte SHA-256 de l'historique utilise (reproductibilite). */
    @Column(length = 64)
    private String dataHash;

    /** JSON : instantane des strategies comparees et de leurs metriques principales (ComparisonDtos.StrategySnapshot). */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String strategiesJson;

    /** JSON : resultat complet (BacktestDtos.ComparisonResult : reglages communs, dates, buy & hold, courbes, metriques). */
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String resultJson;

    private LocalDateTime createdAt;

    /** A la seconde : la base n'en garde pas plus, la reponse a l'enregistrement est ainsi identique aux lectures. */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
