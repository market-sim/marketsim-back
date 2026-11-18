package tn.esprit.marketsim.analysis.fundamental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.FiscalYear;
import tools.jackson.databind.json.JsonMapper;

/**
 * Extraction des donnees annuelles d'un fichier « companyfacts » de la SEC.
 * Le fichier synthetique reprend exactement le format de la SEC, avec des valeurs choisies pour tester chaque regle.
 */
class CompanyFactsParserTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static String resource(String name) {
        try (InputStream in = CompanyFactsParserTest.class.getResourceAsStream("/analysis/fundamental/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("ressource de test introuvable : " + name, e);
        }
    }

    static FundamentalsData parse(String name) {
        return CompanyFactsParser.parse(JSON.readTree(resource(name)));
    }

    private static FundamentalsData synthetic() {
        return parse("sec-companyfacts-synthetique.json");
    }

    @Test
    void keepsTheThreeLatestFiscalYearsFromTheirOwn10K() {
        FundamentalsData d = synthetic();
        assertEquals("0001234567", d.cik(), "CIK sur 10 chiffres");
        assertEquals("Exemple Corp", d.companyName());
        assertEquals(List.of(2025, 2024, 2023), d.years().stream().map(FiscalYear::fiscalYear).toList(),
                "quatre exercices publies, les trois derniers gardes, du plus recent au plus ancien");

        FiscalYear y = d.years().get(0);
        assertEquals(LocalDate.of(2025, 1, 1), y.start());
        assertEquals(LocalDate.of(2025, 12, 31), y.end());
        assertEquals("0001234567-26-000010", y.accession(), "depot 10-K qui a publie l'exercice");
        assertEquals(LocalDate.of(2026, 2, 1), y.filed());
    }

    @Test
    void readsEachDatumOfTheLatestYear() {
        FiscalYear y = synthetic().years().get(0);
        assertEquals(1200, y.value(FundamentalItem.REVENUE));
        assertEquals(150, y.value(FundamentalItem.NET_INCOME));
        assertEquals(1.5, y.value(FundamentalItem.EPS_DILUTED));
        assertEquals(600, y.value(FundamentalItem.EQUITY));
        assertEquals(100, y.value(FundamentalItem.SHARES_OUTSTANDING), "page de garde du 10-K de l'exercice");
    }

    @Test
    void quarterlyFiguresAndAmendmentsAreIgnored() {
        FiscalYear y = synthetic().years().get(0);
        // le fichier contient aussi : 850 (10-Q, 9 mois), 330 (dernier trimestre publie dans le 10-K),
        // 1190 (rectificatif 10-K/A) et des capitaux propres de 550 au 30 juin (10-Q)
        assertEquals(1200, y.value(FundamentalItem.REVENUE), "seul le chiffre annuel du 10-K compte");
        assertEquals(600, y.value(FundamentalItem.EQUITY), "bilan de cloture, pas celui d'un trimestre");
    }

    @Test
    void aRestatedValueComesFromTheMostRecentFiling() {
        // resultat 2024 publie 100 dans le 10-K 2024, puis corrige a 90 dans le 10-K 2025
        FiscalYear y2024 = synthetic().years().get(1);
        assertEquals(90, y2024.value(FundamentalItem.NET_INCOME));
    }

    @Test
    void theFirstLabelThatExistsWinsAndItsNameIsKept() {
        FundamentalsData d = synthetic();
        FiscalYear y2025 = d.years().get(0);
        FiscalYear y2023 = d.years().get(2);
        // 2025 : le 1er libelle de la liste (RevenueFromContract...) n'existe pas -> 2e libelle « Revenues »
        assertEquals("us-gaap:Revenues", y2025.values().get(FundamentalItem.REVENUE).concept());
        // 2023 : les deux libelles existent (900 et 905) -> le 1er de la liste gagne
        assertEquals(900, y2023.value(FundamentalItem.REVENUE));
        assertEquals("us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax",
                y2023.values().get(FundamentalItem.REVENUE).concept());
    }

    @Test
    void debtCanBeTheSumOfSeveralLabels() {
        FiscalYear y = synthetic().years().get(0);
        // ni total publie, ni LongTermDebtCurrent/Noncurrent : regle DebtCurrent + LongTermDebt = 40 + 260
        assertEquals(300, y.value(FundamentalItem.TOTAL_DEBT));
        assertEquals("us-gaap:DebtCurrent + us-gaap:LongTermDebt",
                y.values().get(FundamentalItem.TOTAL_DEBT).concept());
    }

    @Test
    void aMissingDatumStaysAbsent() {
        for (FiscalYear y : synthetic().years()) {
            assertNull(y.value(FundamentalItem.DIVIDEND_PER_SHARE), "aucun dividende publie : absent, jamais 0");
            assertFalse(y.values().containsKey(FundamentalItem.DIVIDEND_PER_SHARE));
        }
    }

    // ===================== Extraits reels de la SEC (AAPL, TSLA) =====================
    // Faits recopies tels quels de data.sec.gov (exercices depuis mi-2023 et trimestres 2026), sans retouche.

    private static FundamentalItem[] all() {
        return FundamentalItem.values();
    }

    @Test
    void appleRealExcerpt() {
        FundamentalsData d = parse("sec-companyfacts-aapl-extrait.json");
        assertEquals("0000320193", d.cik());
        assertEquals("Apple Inc.", d.companyName());
        assertEquals(List.of(2026, 2025, 2024), d.years().stream().map(FiscalYear::fiscalYear).toList(),
                "4 exercices dans l'extrait (2023 a 2026) : les 3 derniers");

        FiscalYear y = d.years().get(0);
        assertEquals(LocalDate.of(2025, 9, 28), y.start());
        assertEquals(LocalDate.of(2026, 9, 26), y.end(), "exercice d'Apple clos fin septembre");
        assertEquals("0000320193-26-000026", y.accession());
        assertEquals(478_834_000_000.0, y.value(FundamentalItem.REVENUE));
        assertEquals(132_175_000_000.0, y.value(FundamentalItem.NET_INCOME));
        assertEquals(8.98, y.value(FundamentalItem.EPS_DILUTED));
        assertEquals(112_093_000_000.0, y.value(FundamentalItem.EQUITY));
        // 9 906 (part courante) + 68 208 (part non courante) + 1 997 (billets de tresorerie) millions
        assertEquals(80_111_000_000.0, y.value(FundamentalItem.TOTAL_DEBT));
        assertEquals("us-gaap:LongTermDebtCurrent + us-gaap:LongTermDebtNoncurrent + us-gaap:CommercialPaper",
                y.values().get(FundamentalItem.TOTAL_DEBT).concept());
        assertEquals(14_532_182_000.0, y.value(FundamentalItem.SHARES_OUTSTANDING), "page de garde au 23/10/2026");
        assertEquals("dei:EntityCommonStockSharesOutstanding", y.values().get(FundamentalItem.SHARES_OUTSTANDING).concept());
        assertEquals(1.06, y.value(FundamentalItem.DIVIDEND_PER_SHARE));
        for (FundamentalItem item : all()) assertTrue(y.values().containsKey(item), item + " trouve chez Apple");

        // les 10-Q de 2026 (9 mois : 364 357 M$) ne remplacent pas le chiffre annuel
        assertEquals(416_161_000_000.0, d.years().get(1).value(FundamentalItem.REVENUE));
        assertEquals(15_115_823_000.0, d.years().get(2).value(FundamentalItem.SHARES_OUTSTANDING),
                "chaque exercice prend la page de garde de son propre 10-K");
    }

    @Test
    void teslaRealExcerpt() {
        FundamentalsData d = parse("sec-companyfacts-tsla-extrait.json");
        assertEquals("0001318605", d.cik());
        assertEquals(List.of(2025, 2024, 2023), d.years().stream().map(FiscalYear::fiscalYear).toList());

        FiscalYear y = d.years().get(0);
        assertEquals(LocalDate.of(2025, 12, 31), y.end());
        assertEquals(94_827_000_000.0, y.value(FundamentalItem.REVENUE));
        assertEquals(3_794_000_000.0, y.value(FundamentalItem.NET_INCOME));
        assertEquals("us-gaap:NetIncomeLoss", y.values().get(FundamentalItem.NET_INCOME).concept(),
                "et non ProfitLoss (3 855 M$, minoritaires compris)");
        assertEquals(1.08, y.value(FundamentalItem.EPS_DILUTED));
        assertEquals(82_137_000_000.0, y.value(FundamentalItem.EQUITY), "hors minoritaires (82 807 M$ avec)");
        // Tesla : DebtCurrent 1 569 + LongTermDebt 6 584 millions (regle de secours, verifiee sur son bilan)
        assertEquals(8_153_000_000.0, y.value(FundamentalItem.TOTAL_DEBT));
        assertEquals("us-gaap:DebtCurrent + us-gaap:LongTermDebt", y.values().get(FundamentalItem.TOTAL_DEBT).concept());
        assertEquals(3_752_431_984.0, y.value(FundamentalItem.SHARES_OUTSTANDING));
        assertNull(y.value(FundamentalItem.DIVIDEND_PER_SHARE), "Tesla ne publie aucun dividende : absent, pas 0");

        // le 10-K 2024 de Tesla publie aussi ses trois premiers trimestres avec fp = FY : ils sont ecartes
        FiscalYear y2024 = d.years().get(1);
        assertEquals(LocalDate.of(2024, 1, 1), y2024.start());
        assertEquals(7_091_000_000.0, y2024.value(FundamentalItem.NET_INCOME), "et non 1 129 M$ (1er trimestre)");
        assertEquals(2.04, y2024.value(FundamentalItem.EPS_DILUTED));
    }

    @Test
    void ratiosOnTheRealExcerptsMatchTheHandCalculation() {
        FundamentalsData apple = parse("sec-companyfacts-aapl-extrait.json");
        var r = FundamentalRatios.compute(apple.years().get(0), apple.years().get(1), 250.0);
        // cours fictif de 250 $
        assertEquals(27.8396436526, value(r, Ratio.PER), 1e-9, "250 / 8,98");
        assertEquals(32.4109935500, value(r, Ratio.PRICE_TO_BOOK), 1e-9, "250 / (112 093 M / 14 532 182 000)");
        assertEquals(1.1791548090, value(r, Ratio.ROE), 1e-9, "132 175 / 112 093 : capitaux propres faibles");
        assertEquals(0.2760351186, value(r, Ratio.NET_MARGIN), 1e-9, "132 175 / 478 834");
        assertEquals(0.7146833433, value(r, Ratio.DEBT_TO_EQUITY), 1e-9, "80 111 / 112 093");
        assertEquals(0.00424, value(r, Ratio.DIVIDEND_YIELD), 1e-12, "1,06 / 250");
        assertEquals(0.1505979657, value(r, Ratio.REVENUE_GROWTH), 1e-9, "478 834 / 416 161 - 1");
        assertEquals(0.1800285689, value(r, Ratio.NET_INCOME_GROWTH), 1e-9, "132 175 / 112 010 - 1");

        FundamentalsData tesla = parse("sec-companyfacts-tsla-extrait.json");
        var t = FundamentalRatios.compute(tesla.years().get(0), tesla.years().get(1), 400.0);
        assertEquals(370.3703703704, value(t, Ratio.PER), 1e-9, "400 / 1,08");
        assertEquals(-0.0293069915, value(t, Ratio.REVENUE_GROWTH), 1e-9, "94 827 / 97 690 - 1 : ventes en baisse");
        assertEquals(-0.4649555775, value(t, Ratio.NET_INCOME_GROWTH), 1e-9, "3 794 / 7 091 - 1");
        assertNull(value(t, Ratio.DIVIDEND_YIELD), "aucun dividende publie");
    }

    private static Double value(List<FundamentalRatios.RatioValue> ratios, Ratio ratio) {
        return ratios.stream().filter(x -> x.ratio() == ratio).findFirst().orElseThrow().value();
    }

    @Test
    void anEmptyFileGivesNoYear() {
        FundamentalsData d = CompanyFactsParser.parse(JSON.readTree("{\"cik\": 42, \"entityName\": \"Vide\", \"facts\": {}}"));
        assertTrue(d.years().isEmpty());
        assertEquals("0000000042", d.cik());
    }
}
