package tn.esprit.marketsim.analysis.fundamental;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Module 3 : cache des donnees fondamentales d'une action (une ligne par action, partagee par tous les
 * utilisateurs). Rafraichi quand il a plus de 30 jours ; garde meme ancien si la source ne repond plus.
 * Cette entite n'est jamais renvoyee telle quelle au front : voir FundamentalsDtos.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class CompanyFundamentals {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Symbole de l'actif du module 5 (ex. AAPL). */
    @Column(nullable = false, unique = true, length = 32)
    private String symbol;

    /** Identifiant de la societe a la SEC, sur 10 chiffres. */
    @Column(length = 10)
    private String cik;

    /** Nom officiel a la SEC. */
    private String companyName;

    /** Source des donnees (ex. SEC_EDGAR). */
    @Column(nullable = false, length = 16)
    private String source;

    /** Date du dernier telechargement reussi. */
    @Column(nullable = false)
    private LocalDateTime downloadedAt;

    /** JSON : donnees extraites (FundamentalsData), avec le libelle XBRL de chaque valeur. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String dataJson;
}
