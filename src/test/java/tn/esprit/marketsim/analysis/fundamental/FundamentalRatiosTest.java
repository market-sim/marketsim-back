package tn.esprit.marketsim.analysis.fundamental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.analysis.fundamental.FundamentalRatios.Benchmark;
import tn.esprit.marketsim.analysis.fundamental.FundamentalRatios.Level;
import tn.esprit.marketsim.analysis.fundamental.FundamentalRatios.RatioValue;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.FiscalYear;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.ReportedValue;

/** Ratios fondamentaux : chaque valeur attendue est calculee a la main dans le commentaire. */
class FundamentalRatiosTest {

    private static final double EPS = 1e-9;

    /** Exercice avec les donnees donnees par paires (donnee, valeur) ; une donnee non donnee est introuvable. */
    private static FiscalYear year(int fy, Object... pairs) {
        Map<FundamentalItem, ReportedValue> values = new EnumMap<>(FundamentalItem.class);
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((FundamentalItem) pairs[i], new ReportedValue(((Number) pairs[i + 1]).doubleValue(), "test"));
        }
        return new FiscalYear(fy, LocalDate.of(fy, 1, 1), LocalDate.of(fy, 12, 31), "accn-" + fy, LocalDate.of(fy + 1, 2, 1),
                values);
    }

    private static FiscalYear complete(int fy, double revenue, double netIncome) {
        return year(fy, FundamentalItem.REVENUE, revenue, FundamentalItem.NET_INCOME, netIncome,
                FundamentalItem.EPS_DILUTED, 1.5, FundamentalItem.EQUITY, 600, FundamentalItem.TOTAL_DEBT, 300,
                FundamentalItem.SHARES_OUTSTANDING, 100, FundamentalItem.DIVIDEND_PER_SHARE, 0.6);
    }

    private static RatioValue get(List<RatioValue> ratios, Ratio ratio) {
        return ratios.stream().filter(r -> r.ratio() == ratio).findFirst().orElseThrow();
    }

    // ===================== Chaque ratio =====================

    @Test
    void everyRatioMatchesTheHandCalculation() {
        // dernier exercice : CA 1200, resultat 150, BPA 1.5, capitaux propres 600, dette 300, 100 actions,
        // dividende 0.6 ; exercice precedent : CA 1000, resultat 90 ; cours 30
        List<RatioValue> r = FundamentalRatios.compute(complete(2025, 1200, 150), complete(2024, 1000, 90), 30.0);

        assertEquals(9, r.size(), "neuf ratios");
        assertEquals(20, get(r, Ratio.PER).value(), EPS, "30 / 1.5");
        assertEquals(5, get(r, Ratio.PRICE_TO_BOOK).value(), EPS, "30 / (600 / 100) = 30 / 6");
        assertEquals(1.5, get(r, Ratio.EPS).value(), EPS, "BPA publie");
        assertEquals(0.25, get(r, Ratio.ROE).value(), EPS, "150 / 600");
        assertEquals(0.125, get(r, Ratio.NET_MARGIN).value(), EPS, "150 / 1200");
        assertEquals(0.5, get(r, Ratio.DEBT_TO_EQUITY).value(), EPS, "300 / 600");
        assertEquals(0.02, get(r, Ratio.DIVIDEND_YIELD).value(), EPS, "0.6 / 30");
        assertEquals(0.2, get(r, Ratio.REVENUE_GROWTH).value(), EPS, "1200 / 1000 - 1");
        assertEquals(150.0 / 90 - 1, get(r, Ratio.NET_INCOME_GROWTH).value(), EPS, "150 / 90 - 1 = 66,7 %");
        for (RatioValue v : r) assertNull(v.unavailableReason(), v.ratio() + " est calculable");
    }

    @Test
    void aMissingDatumGivesAMissingRatioNeverZero() {
        // pas de dividende ni de dette publies
        FiscalYear latest = year(2025, FundamentalItem.REVENUE, 1200, FundamentalItem.NET_INCOME, 150,
                FundamentalItem.EPS_DILUTED, 1.5, FundamentalItem.EQUITY, 600, FundamentalItem.SHARES_OUTSTANDING, 100);
        List<RatioValue> r = FundamentalRatios.compute(latest, complete(2024, 1000, 90), 30.0);

        assertNull(get(r, Ratio.DIVIDEND_YIELD).value(), "jamais 0 quand le dividende est introuvable");
        assertTrue(get(r, Ratio.DIVIDEND_YIELD).unavailableReason().contains("Dividende par action introuvable"));
        assertNull(get(r, Ratio.DEBT_TO_EQUITY).value());
        assertTrue(get(r, Ratio.DEBT_TO_EQUITY).unavailableReason().contains("Dette financière totale introuvable"));
        assertEquals(20, get(r, Ratio.PER).value(), EPS, "les autres ratios restent calcules");
    }

    @Test
    void withoutPriceOnlyThePriceRatiosAreMissing() {
        List<RatioValue> r = FundamentalRatios.compute(complete(2025, 1200, 150), complete(2024, 1000, 90), null);
        for (Ratio ratio : List.of(Ratio.PER, Ratio.PRICE_TO_BOOK, Ratio.DIVIDEND_YIELD)) {
            assertNull(get(r, ratio).value());
            assertEquals(FundamentalRatios.PRICE_MISSING, get(r, ratio).unavailableReason());
        }
        assertEquals(0.25, get(r, Ratio.ROE).value(), EPS, "le ROE ne depend pas du cours");
    }

    @Test
    void ratiosWithoutEconomicMeaningAreLeftEmpty() {
        // entreprise en perte, capitaux propres negatifs, resultat precedent negatif
        FiscalYear latest = year(2025, FundamentalItem.REVENUE, 1200, FundamentalItem.NET_INCOME, -50,
                FundamentalItem.EPS_DILUTED, -0.5, FundamentalItem.EQUITY, -100, FundamentalItem.TOTAL_DEBT, 300,
                FundamentalItem.SHARES_OUTSTANDING, 100);
        FiscalYear previous = year(2024, FundamentalItem.REVENUE, 1000, FundamentalItem.NET_INCOME, -20);
        List<RatioValue> r = FundamentalRatios.compute(latest, previous, 30.0);

        assertNull(get(r, Ratio.PER).value());
        assertTrue(get(r, Ratio.PER).unavailableReason().startsWith("Non significatif"));
        assertNull(get(r, Ratio.PRICE_TO_BOOK).value());
        assertNull(get(r, Ratio.ROE).value());
        assertTrue(get(r, Ratio.ROE).unavailableReason().contains("capitaux propres sont négatifs"));
        assertNull(get(r, Ratio.DEBT_TO_EQUITY).value());
        assertNull(get(r, Ratio.NET_INCOME_GROWTH).value(), "variation en % depuis un resultat negatif : pas de sens");
        assertEquals(-0.5, get(r, Ratio.EPS).value(), EPS, "un BPA negatif reste une information");
        assertEquals(-50.0 / 1200, get(r, Ratio.NET_MARGIN).value(), EPS, "marge negative : -50 / 1200");
        assertEquals(0.2, get(r, Ratio.REVENUE_GROWTH).value(), EPS);
    }

    @Test
    void growthNeedsAPreviousYear() {
        List<RatioValue> r = FundamentalRatios.compute(complete(2025, 1200, 150), null, 30.0);
        assertNull(get(r, Ratio.REVENUE_GROWTH).value());
        assertEquals(FundamentalRatios.ONE_YEAR_ONLY, get(r, Ratio.REVENUE_GROWTH).unavailableReason());
        assertNull(get(r, Ratio.NET_INCOME_GROWTH).value());
    }

    // ===================== Repere =====================

    private static Map<String, Double> values(Object... pairs) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Double) pairs[i + 1]);
        return m;
    }

    @Test
    void threeStocksAreLowMediumAndHigh() {
        Map<String, Benchmark> b = FundamentalRatios.benchmark(values("A", 10.0, "B", 20.0, "C", 30.0));
        assertEquals(new Benchmark(Level.LOW, 3), b.get("A"));
        assertEquals(new Benchmark(Level.MEDIUM, 3), b.get("B"));
        assertEquals(new Benchmark(Level.HIGH, 3), b.get("C"));
    }

    @Test
    void sevenStocksAreSplitInThirds() {
        // positions 0, 1/6, 2/6, 3/6, 4/6, 5/6, 1 : bas sous 1/3, eleve au-dessus de 2/3
        Map<String, Benchmark> b = FundamentalRatios.benchmark(values("A", 1.0, "B", 2.0, "C", 3.0, "D", 4.0,
                "E", 5.0, "F", 6.0, "G", 7.0));
        assertEquals(Level.LOW, b.get("A").level());
        assertEquals(Level.LOW, b.get("B").level());
        assertEquals(Level.MEDIUM, b.get("C").level(), "position 1/3 exactement : moyen");
        assertEquals(Level.MEDIUM, b.get("D").level());
        assertEquals(Level.MEDIUM, b.get("E").level(), "position 2/3 exactement : moyen");
        assertEquals(Level.HIGH, b.get("F").level());
        assertEquals(Level.HIGH, b.get("G").level());
    }

    @Test
    void equalValuesGetTheSameLevel() {
        // A et B a egalite aux rangs 0 et 1 : position (0 + 1) / 2 / 2 = 0,25 -> bas
        Map<String, Benchmark> b = FundamentalRatios.benchmark(values("A", 10.0, "B", 10.0, "C", 30.0));
        assertEquals(Level.LOW, b.get("A").level());
        assertEquals(Level.LOW, b.get("B").level());
        assertEquals(Level.HIGH, b.get("C").level());
    }

    @Test
    void missingValuesAreNotRankedAndOneStockAloneHasNoLevel() {
        Map<String, Benchmark> b = FundamentalRatios.benchmark(values("A", 10.0, "B", null, "C", 30.0));
        assertNull(b.get("B"), "une action sans valeur n'est pas classee");
        assertEquals(new Benchmark(Level.LOW, 2), b.get("A"), "deux actions : la plus basse est basse");
        assertEquals(new Benchmark(Level.HIGH, 2), b.get("C"));

        Map<String, Benchmark> alone = FundamentalRatios.benchmark(values("A", 10.0, "B", null));
        assertEquals(new Benchmark(null, 1), alone.get("A"), "seule a avoir ce ratio : pas de repere");
    }
}
