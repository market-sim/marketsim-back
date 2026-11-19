package tn.esprit.marketsim.analysis.fundamental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.analysis.fundamental.XbrlInstanceParser.InstanceShares;

/**
 * Lecture du nombre d'actions dans l'instance XBRL d'un 10-K. Extraits reels recopies tels quels depuis les depots
 * d'Alphabet et de Meta sur www.sec.gov (contextes et faits du nombre d'actions uniquement).
 */
class XbrlInstanceParserTest {

    private static InstanceShares read(String name) {
        return XbrlInstanceParser.parse(CompanyFactsParserTest.resource(name));
    }

    @Test
    void alphabetCoverPageAddsItsThreeClasses() {
        InstanceShares s = read("sec-instance-googl-extrait.xml");
        // page de garde du 10-K 2025 : classe A 5 822 M + classe B 837 M + classe C 5 438 M
        assertEquals(LocalDate.of(2026, 1, 28), s.coverDate());
        assertEquals(12_097_000_000.0, s.cover().value());
        assertEquals("somme de 3 catégories", s.cover().how());
        assertEquals(" (somme de 3 catégories)", s.cover().labelSuffix());
    }

    @Test
    void aFactWithoutDimensionIsAlreadyTheTotal() {
        InstanceShares s = read("sec-instance-googl-extrait.xml");
        // au bilan, Alphabet publie le total (sans dimension) ET chaque classe : on prend le total, sans double compte
        assertEquals(12_088_000_000.0, s.yearEnd().get(LocalDate.of(2025, 12, 31)).value());
        assertEquals("", s.yearEnd().get(LocalDate.of(2025, 12, 31)).how());
        assertEquals(12_211_000_000.0, s.yearEnd().get(LocalDate.of(2024, 12, 31)).value());
        assertEquals(2, s.yearEnd().size());
    }

    @Test
    void metaCoverPageAndEquityStatementTotals() {
        InstanceShares s = read("sec-instance-meta-extrait.xml");
        // classe A 2 187 177 748 + classe B 342 377 716, au 23/01/2026
        assertEquals(LocalDate.of(2026, 1, 23), s.coverDate());
        assertEquals(2_529_555_464.0, s.cover().value());
        assertEquals("somme de 2 catégories", s.cover().how());
        // le tableau des capitaux propres donne le total de 4 clotures (2022 a 2025), retraite dans ce 10-K
        assertEquals(2_614_000_000.0, s.yearEnd().get(LocalDate.of(2022, 12, 31)).value());
        assertEquals(2_561_000_000.0, s.yearEnd().get(LocalDate.of(2023, 12, 31)).value());
        assertEquals("tableau des capitaux propres", s.yearEnd().get(LocalDate.of(2023, 12, 31)).how());
        // 2025 : total publie 2 530 M = classe A 2 187 M + classe B 343 M (classe A publiee deux fois : comptee une fois)
        assertEquals(2_530_000_000.0, s.yearEnd().get(LocalDate.of(2025, 12, 31)).value());
    }

    @Test
    void classesAreSummedWhenNoTotalIsPublished() {
        // memes faits Meta au 31/12/2025 sans le total du tableau des capitaux propres : la somme des categories
        String xml = CompanyFactsParserTest.resource("sec-instance-meta-extrait.xml")
                .replaceAll("<us-gaap:CommonStockSharesOutstanding contextRef=\"c-39\"[^\n]*\n", "");
        InstanceShares s = XbrlInstanceParser.parse(xml);
        assertEquals(2_530_000_000.0, s.yearEnd().get(LocalDate.of(2025, 12, 31)).value(), "2 187 M + 343 M");
        assertEquals("somme de 2 catégories", s.yearEnd().get(LocalDate.of(2025, 12, 31)).how());
    }

    @Test
    void anInstanceWithoutShareCountsGivesNothing() {
        InstanceShares s = XbrlInstanceParser.parse(
                "<xbrl xmlns=\"http://www.xbrl.org/2003/instance\"><context id=\"c-1\"><period><instant>2025-12-31"
                        + "</instant></period></context></xbrl>");
        assertNull(s.cover());
        assertNull(s.coverDate());
        assertEquals(0, s.yearEnd().size());
    }

    @Test
    void anUnreadableFileIsRejectedWithAClearError() {
        assertThrows(IllegalArgumentException.class, () -> XbrlInstanceParser.parse("<html>maintenance</xbrl>"));
        assertThrows(IllegalArgumentException.class, () -> XbrlInstanceParser.parse("pas du XML"));
    }
}
