package tn.esprit.marketsim.analysis.fundamental;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.FiscalYear;

/**
 * Module 3 : calcul des ratios fondamentaux et de leur repere (fonctions pures).
 *
 * <p>Les ratios portent sur le dernier exercice publie ; ceux qui utilisent le cours prennent la derniere cloture
 * connue (le marche reagit a des comptes qui datent parfois de plusieurs mois). Une donnee absente donne un ratio
 * absent, avec sa raison : on ne remplace jamais une valeur manquante par 0. Un ratio sans sens economique (PER
 * d'une entreprise en perte, capitaux propres negatifs...) est aussi laisse vide, avec l'explication.
 */
public final class FundamentalRatios {

    private FundamentalRatios() {
    }

    /** Valeur d'un ratio, ou la raison pour laquelle il n'est pas calcule. */
    public record RatioValue(Ratio ratio, Double value, String unavailableReason) {
    }

    /** Position par rapport aux autres actions analysees : premier, deuxieme ou dernier tiers. */
    public enum Level { LOW, MEDIUM, HIGH }

    /**
     * @param level    null si la comparaison est impossible (moins de deux actions ont ce ratio)
     * @param compared nombre d'actions qui ont ce ratio
     */
    public record Benchmark(Level level, int compared) {
    }

    static final String PRICE_MISSING = "Cours de l'action indisponible.";
    static final String ONE_YEAR_ONLY = "Un seul exercice disponible : pas de comparaison sur un an.";
    /** Aucun dividende dans le 10-K : on ne l'affiche pas comme 0 %, faute de preuve, mais on dit ce que cela signifie. */
    static final String NO_DIVIDEND = "Aucun dividende par action publié dans le rapport 10-K : "
            + "en général, la société n'en verse pas.";

    // ===================== Ratios =====================

    /**
     * @param latest   dernier exercice
     * @param previous exercice precedent, ou null
     * @param price    derniere cloture, ou null si inconnue
     */
    public static List<RatioValue> compute(FiscalYear latest, FiscalYear previous, Double price) {
        Double revenue = latest.value(FundamentalItem.REVENUE);
        Double netIncome = latest.value(FundamentalItem.NET_INCOME);
        Double eps = latest.value(FundamentalItem.EPS_DILUTED);
        Double equity = latest.value(FundamentalItem.EQUITY);
        Double debt = latest.value(FundamentalItem.TOTAL_DEBT);
        Double shares = latest.value(FundamentalItem.SHARES_OUTSTANDING);
        Double dividend = latest.value(FundamentalItem.DIVIDEND_PER_SHARE);

        List<RatioValue> out = new ArrayList<>();
        out.add(per(price, eps));
        out.add(priceToBook(price, equity, shares));
        out.add(eps == null ? notFound(Ratio.EPS, FundamentalItem.EPS_DILUTED) : value(Ratio.EPS, eps));
        out.add(divide(Ratio.ROE, netIncome, FundamentalItem.NET_INCOME, equity, FundamentalItem.EQUITY));
        out.add(divide(Ratio.NET_MARGIN, netIncome, FundamentalItem.NET_INCOME, revenue, FundamentalItem.REVENUE));
        out.add(divide(Ratio.DEBT_TO_EQUITY, debt, FundamentalItem.TOTAL_DEBT, equity, FundamentalItem.EQUITY));
        out.add(dividendYield(dividend, price));
        out.add(growth(Ratio.REVENUE_GROWTH, FundamentalItem.REVENUE, latest, previous));
        out.add(growth(Ratio.NET_INCOME_GROWTH, FundamentalItem.NET_INCOME, latest, previous));
        return out;
    }

    /** PER = cours / BPA. Non significatif si le BPA est negatif ou nul (entreprise en perte). */
    static RatioValue per(Double price, Double eps) {
        if (price == null) return missing(Ratio.PER, PRICE_MISSING);
        if (eps == null) return notFound(Ratio.PER, FundamentalItem.EPS_DILUTED);
        if (eps <= 0) {
            return missing(Ratio.PER, "Non significatif : le bénéfice par action est négatif ou nul.");
        }
        return value(Ratio.PER, price / eps);
    }

    /** Cours / valeur comptable par action, avec valeur comptable par action = capitaux propres / nombre d'actions. */
    static RatioValue priceToBook(Double price, Double equity, Double shares) {
        if (price == null) return missing(Ratio.PRICE_TO_BOOK, PRICE_MISSING);
        if (equity == null) return notFound(Ratio.PRICE_TO_BOOK, FundamentalItem.EQUITY);
        if (shares == null || shares <= 0) return notFound(Ratio.PRICE_TO_BOOK, FundamentalItem.SHARES_OUTSTANDING);
        if (equity <= 0) {
            return missing(Ratio.PRICE_TO_BOOK, "Non significatif : " + nonPositive(FundamentalItem.EQUITY) + ".");
        }
        return value(Ratio.PRICE_TO_BOOK, price / (equity / shares));
    }

    /**
     * Rendement du dividende = dividende par action / cours. Sans dividende publie, le ratio reste vide (jamais 0 %)
     * avec {@link #NO_DIVIDEND} : une societe qui verse un dividende le publie dans son 10-K.
     */
    static RatioValue dividendYield(Double dividend, Double price) {
        if (dividend == null) return missing(Ratio.DIVIDEND_YIELD, NO_DIVIDEND);
        if (price == null) return missing(Ratio.DIVIDEND_YIELD, PRICE_MISSING);
        return value(Ratio.DIVIDEND_YIELD, dividend / price);
    }

    /** a / b, ou b doit etre strictement positif pour que le ratio ait un sens (capitaux propres, chiffre d'affaires). */
    static RatioValue divide(Ratio ratio, Double a, FundamentalItem aItem, Double b, FundamentalItem bItem) {
        if (a == null) return notFound(ratio, aItem);
        if (b == null) return notFound(ratio, bItem);
        if (b <= 0) {
            return missing(ratio, "Non significatif : " + nonPositive(bItem) + ".");
        }
        return value(ratio, a / b);
    }

    /** Variation sur un an = valeur / valeur precedente - 1. Pas de sens si la valeur precedente est <= 0. */
    static RatioValue growth(Ratio ratio, FundamentalItem item, FiscalYear latest, FiscalYear previous) {
        if (previous == null) return missing(ratio, ONE_YEAR_ONLY);
        Double now = latest.value(item);
        Double before = previous.value(item);
        if (now == null || before == null) return notFound(ratio, item);
        if (before <= 0) {
            return missing(ratio, "Non significatif : " + nonPositive(item)
                    + " sur l'exercice précédent (une variation en % n'aurait pas de sens).");
        }
        return value(ratio, now / before - 1);
    }

    /** « les capitaux propres sont negatifs ou nuls », « le resultat net est negatif ou nul »... */
    private static String nonPositive(FundamentalItem item) {
        return switch (item) {
            case EQUITY -> "les capitaux propres sont négatifs ou nuls";
            case REVENUE -> "le chiffre d'affaires est nul ou négatif";
            case NET_INCOME -> "le résultat net est négatif ou nul";
            default -> item.label() + " : valeur négative ou nulle";
        };
    }

    private static RatioValue value(Ratio ratio, double v) {
        return new RatioValue(ratio, v, null);
    }

    private static RatioValue missing(Ratio ratio, String reason) {
        return new RatioValue(ratio, null, reason);
    }

    private static RatioValue notFound(Ratio ratio, FundamentalItem item) {
        return missing(ratio, item.label() + " introuvable dans le rapport 10-K.");
    }

    // ===================== Repere =====================

    /**
     * Repere d'un ratio pour chaque action, par rapport aux autres actions analysees.
     *
     * <p>Les valeurs connues sont classees de la plus basse a la plus haute ; la position d'une action va de 0 (la
     * plus basse) a 1 (la plus haute) : {@code position = rang / (n - 1)}. Premier tiers : bas, dernier tiers : eleve,
     * entre les deux : moyen. Des valeurs egales recoivent la meme position (la moyenne de leurs rangs).
     * Le repere est neutre : un PER « eleve » veut dire « cher par rapport aux autres », pas « bon ».
     *
     * @param values valeur du ratio par symbole (null = non disponible : l'action n'est pas classee)
     */
    public static Map<String, Benchmark> benchmark(Map<String, Double> values) {
        List<Map.Entry<String, Double>> known = values.entrySet().stream()
                .filter(e -> e.getValue() != null && Double.isFinite(e.getValue()))
                .sorted(Map.Entry.comparingByValue())
                .toList();
        int n = known.size();
        Map<String, Benchmark> out = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (n < 2) {
                out.put(known.get(i).getKey(), new Benchmark(null, n));
                continue;
            }
            double v = known.get(i).getValue();
            int first = i;
            while (first > 0 && known.get(first - 1).getValue() == v) first--;
            int last = i;
            while (last < n - 1 && known.get(last + 1).getValue() == v) last++;
            double position = (first + last) / 2.0 / (n - 1);
            Level level = position < 1.0 / 3 - 1e-9 ? Level.LOW : position > 2.0 / 3 + 1e-9 ? Level.HIGH : Level.MEDIUM;
            out.put(known.get(i).getKey(), new Benchmark(level, n));
        }
        return out;
    }
}
