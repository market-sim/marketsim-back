package tn.esprit.marketsim.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchServiceTest {

    @Test
    void exactAndPrefixMatchesRankFirst() {
        assertTrue(SearchService.score("aapl", "AAPL", "Apple") > SearchService.score("aapl", "AAPLX", "x"));
        assertTrue(SearchService.score("app", "Apple Inc.") > SearchService.score("app", "Snapple"));
        assertTrue(SearchService.score("app", "AAPL", "Apple Inc.") > 0);
        assertEquals(0, SearchService.score("tesla", "AAPL", "Apple"));
    }

    @Test
    void accentsAndCaseAreIgnored() {
        assertTrue(SearchService.score(SearchService.normalize("Pétrole"), "PETROLE") > 0);
        assertTrue(SearchService.score(SearchService.normalize("yasmine"), "Yasmine S.") > 0);
        assertTrue(SearchService.score("strategie", "Stratégie de momentum") > 0);
    }

    @Test
    void titleWeighsMoreThanDescription() {
        assertTrue(SearchService.score("risque", "Gestion du risque", "x") > SearchService.score("risque", "Cours", "gestion du risque"));
    }
}
