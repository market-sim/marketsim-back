package tn.esprit.marketsim.agent.realdata;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Historique journalier reel d'un actif (cours de cloture ajustes), garde en base : une ligne par symbole.
 * Le contenu est un texte {@code yyyy-MM-dd,prix} (un point par ligne) dont l'empreinte SHA-256 identifie
 * le jeu de donnees exact utilise par un backtest (reproductibilite et audit).
 */
@Entity
@Table(name = "market_series", uniqueConstraints = @UniqueConstraint(columnNames = "symbol"))
@Getter
@Setter
@NoArgsConstructor
public class MarketSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    /** YAHOO (telechargement automatique) ou CSV (import manuel). */
    @Column(nullable = false, length = 16)
    private String source;

    private LocalDate firstDate;

    private LocalDate lastDate;

    private int points;

    @Column(length = 64)
    private String dataHash;

    private LocalDateTime fetchedAt;

    @Column(columnDefinition = "LONGTEXT")
    private String data;
}
