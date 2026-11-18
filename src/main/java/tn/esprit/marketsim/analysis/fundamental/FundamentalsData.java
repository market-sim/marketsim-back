package tn.esprit.marketsim.analysis.fundamental;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Module 3 : donnees fondamentales extraites pour une societe (gardees en JSON dans {@link CompanyFundamentals}).
 *
 * @param cik         identifiant de la societe a la SEC (10 chiffres)
 * @param companyName nom officiel a la SEC
 * @param years       exercices du plus recent au plus ancien (trois au plus)
 */
public record FundamentalsData(String cik, String companyName, List<FiscalYear> years) {

    /**
     * Un exercice publie dans un rapport annuel 10-K.
     *
     * @param fiscalYear numero d'exercice donne par la societe (ex. 2026 pour l'exercice d'Apple clos en septembre 2026)
     * @param start      premier jour de l'exercice
     * @param end        date de cloture
     * @param accession  numero du depot 10-K qui a publie cet exercice pour la premiere fois
     * @param filed      date de ce depot
     * @param values     donnees trouvees ; une donnee introuvable est simplement absente (jamais 0)
     */
    public record FiscalYear(int fiscalYear, LocalDate start, LocalDate end, String accession, LocalDate filed,
                             Map<FundamentalItem, ReportedValue> values) {

        /** Valeur publiee, ou null si elle est introuvable. */
        public Double value(FundamentalItem item) {
            ReportedValue v = values.get(item);
            return v == null ? null : v.value();
        }
    }

    /**
     * Valeur publiee et libelle(s) XBRL d'ou elle vient.
     *
     * @param concept ex. {@code us-gaap:NetIncomeLoss}, ou {@code us-gaap:DebtCurrent + us-gaap:LongTermDebt} pour une somme
     */
    public record ReportedValue(double value, String concept) {
    }
}
