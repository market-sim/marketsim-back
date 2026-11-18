package tn.esprit.marketsim.analysis.fundamental;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import tn.esprit.marketsim.analysis.fundamental.FundamentalRatios.Level;

/**
 * Module 3 : objets echanges avec le front pour l'analyse fondamentale. Les taux sont des fractions (0.25 = 25 %),
 * comme partout dans l'API du module 3. Une valeur {@code null} veut dire « non disponible » (jamais 0).
 */
public final class FundamentalsDtos {

    private FundamentalsDtos() {
    }

    /** Etat des donnees en cache : a jour (moins de 30 jours), anciennes, ou jamais telechargees. */
    public enum DataStatus { FRESH, STALE, MISSING }

    /**
     * Une donnee d'un exercice.
     *
     * @param value   null = introuvable dans le 10-K
     * @param concept libelle(s) XBRL d'ou vient la valeur, null si introuvable
     */
    public record ItemView(FundamentalItem item, String label, FundamentalItem.Unit unit, Double value,
                           String concept) {
    }

    /**
     * Un exercice publie dans un rapport 10-K.
     *
     * @param reportUrl page du depot sur le site de la source, pour verifier les chiffres
     */
    public record YearView(int fiscalYear, LocalDate start, LocalDate end, String accession, LocalDate filed,
                           String reportUrl, List<ItemView> items) {
    }

    /**
     * Un ratio, son explication et son repere.
     *
     * @param value             null si non calcule (voir {@code unavailableReason})
     * @param level             position par rapport aux autres actions analysees, null si pas de comparaison
     * @param compared          nombre d'actions qui ont ce ratio (celle-ci comprise)
     */
    public record RatioView(Ratio ratio, String label, Ratio.Unit unit, String formula, String explanation,
                            Double value, String unavailableReason, Level level, int compared) {
    }

    /**
     * Analyse fondamentale complete d'une action.
     *
     * @param status    FRESH, ou STALE si la source n'a pas repondu et que le cache ancien est utilise
     * @param warning   avertissement a afficher (cache ancien), null sinon
     * @param price     derniere cloture connue (MarketDataService), null si indisponible
     * @param years     trois derniers exercices, du plus recent au plus ancien
     */
    public record Detail(String symbol, String name, String cik, String companyName, String source,
                         LocalDateTime downloadedAt, long ageDays, DataStatus status, String warning,
                         Double price, LocalDate priceDate, List<YearView> years, List<RatioView> ratios) {
    }

    /** Ligne du tableau comparatif : ratios calcules a partir du cache, sans appel a la source. */
    public record Summary(String symbol, String name, DataStatus status, LocalDateTime downloadedAt,
                          Integer fiscalYear, LocalDate fiscalYearEnd, List<RatioView> ratios) {
    }

    /**
     * Toutes les actions de la plateforme et l'etat de leurs donnees.
     *
     * @param configurationProblem probleme de configuration de la source (ex. User-Agent SEC absent), null sinon
     */
    public record Overview(String source, int refreshAfterDays, String configurationProblem, List<Summary> stocks) {
    }
}
