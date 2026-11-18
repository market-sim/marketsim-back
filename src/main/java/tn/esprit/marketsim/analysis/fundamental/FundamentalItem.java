package tn.esprit.marketsim.analysis.fundamental;

/**
 * Module 3 : donnees lues dans les rapports annuels 10-K, pour chaque exercice.
 *
 * <p>Une donnee « sur l'exercice » (chiffre d'affaires, resultat...) couvre toute l'annee ; une donnee « a la cloture »
 * (capitaux propres, dette...) est une photo du bilan le dernier jour de l'exercice.
 */
public enum FundamentalItem {

    REVENUE("Chiffre d'affaires", Unit.USD),
    // part du groupe (« Net income attributable to ... » dans le 10-K) : celle qui sert au BPA, au PER et au ROE.
    // Une societe avec des minoritaires publie aussi un « Net income » plus large (Tesla 2025 : 3 855 M$ contre 3 794).
    NET_INCOME("Résultat net part du groupe", Unit.USD),
    EPS_DILUTED("Bénéfice par action dilué", Unit.USD_PER_SHARE),
    EQUITY("Capitaux propres", Unit.USD),
    TOTAL_DEBT("Dette financière totale", Unit.USD),
    SHARES_OUTSTANDING("Nombre d'actions", Unit.SHARES),
    DIVIDEND_PER_SHARE("Dividende par action", Unit.USD_PER_SHARE);

    /** Unite de la donnee, avec son nom dans les fichiers XBRL de la SEC. */
    public enum Unit {
        USD("USD"),
        USD_PER_SHARE("USD/shares"),
        SHARES("shares");

        private final String xbrl;

        Unit(String xbrl) {
            this.xbrl = xbrl;
        }

        public String xbrl() {
            return xbrl;
        }
    }

    private final String label;
    private final Unit unit;

    FundamentalItem(String label, Unit unit) {
        this.label = label;
        this.unit = unit;
    }

    public String label() {
        return label;
    }

    public Unit unit() {
        return unit;
    }
}
