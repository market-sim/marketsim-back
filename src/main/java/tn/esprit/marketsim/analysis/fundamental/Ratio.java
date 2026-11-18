package tn.esprit.marketsim.analysis.fundamental;

/**
 * Module 3 : les ratios fondamentaux, avec leur formule et une explication pour un debutant (renvoyees au front :
 * le texte n'existe qu'a un seul endroit).
 *
 * <p>Unites : {@link Unit#MULTIPLE} = nombre de fois (ex. PER de 25), {@link Unit#USD} = dollars par action,
 * {@link Unit#RATE} = fraction (0.25 = 25 %), comme tous les taux de l'API du module 3.
 */
public enum Ratio {

    PER("PER (cours / bénéfice)", Unit.MULTIPLE,
            "Cours ÷ bénéfice par action dilué",
            "Combien d'années du bénéfice actuel on paie en achetant l'action au cours du jour. Un PER élevé veut "
                    + "dire que le marché attend une forte croissance, ou que l'action est chère."),
    PRICE_TO_BOOK("Cours / valeur comptable", Unit.MULTIPLE,
            "Cours ÷ (capitaux propres ÷ nombre d'actions)",
            "Compare le prix de l'action à ce que vaut, dans les comptes, la part de l'entreprise qui revient à "
                    + "chaque action. Au-dessus de 1, on paie plus que la valeur comptable : c'est courant quand la "
                    + "valeur vient surtout des marques, brevets ou logiciels, qui ne figurent pas au bilan."),
    EPS("Bénéfice par action (dilué)", Unit.USD,
            "Résultat net ÷ nombre moyen d'actions dilué (publié dans le 10-K)",
            "Part du bénéfice annuel qui revient à une action. « Dilué » : on compte aussi les actions qui "
                    + "pourraient être créées (options et actions gratuites des salariés)."),
    ROE("Rentabilité des capitaux propres (ROE)", Unit.RATE,
            "Résultat net ÷ capitaux propres",
            "Bénéfice produit par chaque dollar des actionnaires. Attention : un ROE très élevé peut aussi venir de "
                    + "capitaux propres faibles (rachats d'actions, dette), pas seulement d'une forte rentabilité."),
    NET_MARGIN("Marge nette", Unit.RATE,
            "Résultat net ÷ chiffre d'affaires",
            "Ce qu'il reste en bénéfice sur 100 $ de ventes, une fois toutes les charges et les impôts payés."),
    DEBT_TO_EQUITY("Dette sur capitaux propres", Unit.MULTIPLE,
            "Dette financière totale ÷ capitaux propres",
            "Mesure l'endettement : 1 signifie autant de dettes que de capitaux propres. Plus il est élevé, plus "
                    + "l'entreprise dépend de ses prêteurs. Les contrats de location ne sont pas comptés."),
    DIVIDEND_YIELD("Rendement du dividende", Unit.RATE,
            "Dividende annuel par action ÷ cours",
            "Ce que l'action rapporte chaque année en dividendes, rapporté à son prix. Beaucoup d'entreprises de "
                    + "croissance versent peu ou pas de dividende et réinvestissent leurs bénéfices."),
    REVENUE_GROWTH("Croissance du chiffre d'affaires", Unit.RATE,
            "CA de l'exercice ÷ CA de l'exercice précédent − 1",
            "Évolution des ventes sur un an."),
    NET_INCOME_GROWTH("Croissance du résultat net", Unit.RATE,
            "Résultat de l'exercice ÷ résultat de l'exercice précédent − 1",
            "Évolution du bénéfice sur un an. Elle varie souvent bien plus que les ventes (charges exceptionnelles, "
                    + "impôts, effet de levier des coûts fixes).");

    public enum Unit { MULTIPLE, USD, RATE }

    private final String label;
    private final Unit unit;
    private final String formula;
    private final String explanation;

    Ratio(String label, Unit unit, String formula, String explanation) {
        this.label = label;
        this.unit = unit;
        this.formula = formula;
        this.explanation = explanation;
    }

    public String label() {
        return label;
    }

    public Unit unit() {
        return unit;
    }

    public String formula() {
        return formula;
    }

    public String explanation() {
        return explanation;
    }
}
