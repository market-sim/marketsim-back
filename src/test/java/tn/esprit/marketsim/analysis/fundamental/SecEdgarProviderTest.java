package tn.esprit.marketsim.analysis.fundamental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.analysis.fundamental.SecHttpClient.Response;
import tn.esprit.marketsim.common.NotFoundException;

/** Fournisseur SEC EDGAR : le client HTTP est simule (Mockito), aucun test n'appelle le reseau. */
class SecEdgarProviderTest {

    private static final String UA = "MarketSim test contact@example.test";
    private static final String TICKERS = CompanyFactsParserTest.resource("sec-company-tickers-extrait.json");
    private static final String FACTS = CompanyFactsParserTest.resource("sec-companyfacts-synthetique.json");
    private static final String FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK0001234567.json";

    private SecHttpClient http;
    private MutableClock clock;
    private SecEdgarProvider provider;

    /** Horloge qu'on fait avancer a la main. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-17T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        http = mock(SecHttpClient.class);
        clock = new MutableClock();
        provider = new SecEdgarProvider(http, CompanyFactsParserTest.JSON, UA, clock);
        when(http.get(SecEdgarProvider.TICKERS_URL, UA)).thenReturn(new Response(200, TICKERS));
        when(http.get(FACTS_URL, UA)).thenReturn(new Response(200, FACTS));
        // aucun fichier de depot par defaut (le fichier synthetique n'a pas de nombre d'actions a la cloture)
        when(http.get(startsWith("https://www.sec.gov/Archives/"), eq(UA))).thenReturn(new Response(404, ""));
    }

    // ===================== Plusieurs categories d'actions : instance XBRL du dernier 10-K =====================

    private static final String META_FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK0001326801.json";
    private static final String META_FOLDER = "https://www.sec.gov/Archives/edgar/data/1326801/000162828026003942/";

    private void metaFiles() throws Exception {
        when(http.get(META_FACTS_URL, UA)).thenReturn(
                new Response(200, CompanyFactsParserTest.resource("sec-companyfacts-meta-extrait.json")));
        when(http.get(META_FOLDER + "index.json", UA)).thenReturn(
                new Response(200, CompanyFactsParserTest.resource("sec-index-meta-extrait.json")));
        when(http.get(META_FOLDER + "meta-20251231_htm.xml", UA)).thenReturn(
                new Response(200, CompanyFactsParserTest.resource("sec-instance-meta-extrait.xml")));
    }

    @Test
    void multiClassSharesAreReadInTheInstanceOfTheLatest10K() throws Exception {
        metaFiles();
        FundamentalsData d = provider.fetch("META", "0001326801");
        assertEquals(2_529_555_464.0, d.years().get(0).value(FundamentalItem.SHARES_OUTSTANDING),
                "page de garde : classes A et B additionnees");
        assertEquals(2_561_000_000.0, d.years().get(2).value(FundamentalItem.SHARES_OUTSTANDING));
        // liste des fichiers du depot, puis l'instance : jamais les autres fichiers XBRL (_cal, _lab...)
        verify(http).get(META_FOLDER + "index.json", UA);
        verify(http).get(META_FOLDER + "meta-20251231_htm.xml", UA);
        verify(http, times(2)).get(startsWith("https://www.sec.gov/Archives/"), eq(UA));
    }

    @Test
    void anUnreachableInstanceDoesNotBlockTheAnalysis() throws Exception {
        metaFiles();
        when(http.get(META_FOLDER + "meta-20251231_htm.xml", UA)).thenReturn(new Response(503, ""));
        FundamentalsData d = provider.fetch("META", "0001326801");
        assertEquals(200_966_000_000.0, d.years().get(0).value(FundamentalItem.REVENUE), "le reste est la");
        assertNull(d.years().get(0).value(FundamentalItem.SHARES_OUTSTANDING), "non disponible, jamais invente");

        when(http.get(META_FOLDER + "meta-20251231_htm.xml", UA)).thenReturn(new Response(200, "<html>maintenance"));
        assertNull(provider.fetch("META", "0001326801").years().get(0).value(FundamentalItem.SHARES_OUTSTANDING),
                "instance illisible : meme chose");
    }

    @Test
    void aSingleClassCompanyNeedsNoExtraRequest() throws Exception {
        when(http.get("https://data.sec.gov/api/xbrl/companyfacts/CIK0001318605.json", UA)).thenReturn(
                new Response(200, CompanyFactsParserTest.resource("sec-companyfacts-tsla-extrait.json")));
        FundamentalsData d = provider.fetch("TSLA", "0001318605");
        assertEquals(3_752_431_984.0, d.years().get(0).value(FundamentalItem.SHARES_OUTSTANDING));
        verify(http, never()).get(startsWith("https://www.sec.gov/Archives/"), anyString());
    }

    @Test
    void theInstanceIsTheExtractedXbrlFile() {
        assertEquals("meta-20251231_htm.xml", SecEdgarProvider.instanceFileName(
                CompanyFactsParserTest.JSON.readTree(CompanyFactsParserTest.resource("sec-index-meta-extrait.json"))));
        assertNull(SecEdgarProvider.instanceFileName(CompanyFactsParserTest.JSON.readTree(
                "{\"directory\": {\"item\": [{\"name\": \"aapl-20200926.xml\"}, {\"name\": \"FilingSummary.xml\"}]}}")),
                "pas d'instance extraite (ancien depot non « inline ») : rien");
    }

    @Test
    void findsTheCikThenReadsTheFactsWithTheUserAgent() throws Exception {
        FundamentalsData d = provider.fetch("EXMP", null);
        assertEquals("0001234567", d.cik());
        assertEquals(3, d.years().size());
        verify(http).get(SecEdgarProvider.TICKERS_URL, UA);
        verify(http).get(FACTS_URL, UA);
        assertEquals(SecEdgarProvider.SOURCE, provider.source());
        assertNull(provider.configurationProblem());
    }

    @Test
    void aKnownCikSkipsTheTickerList() throws Exception {
        provider.fetch("EXMP", "0001234567");
        verify(http, never()).get(SecEdgarProvider.TICKERS_URL, UA);
    }

    @Test
    void theTickerListIsKeptInMemoryFor24Hours() throws Exception {
        provider.fetch("EXMP", null);
        clock.now = clock.now.plus(Duration.ofHours(23));
        provider.fetch("EXMP", null);
        verify(http, times(1)).get(SecEdgarProvider.TICKERS_URL, UA);
        clock.now = clock.now.plus(Duration.ofHours(2));
        provider.fetch("EXMP", null);
        verify(http, times(2)).get(SecEdgarProvider.TICKERS_URL, UA);
    }

    @Test
    void symbolsAreMatchedLikeTheSecWritesThem() {
        assertEquals("0000320193", provider.cik("aapl"));
        assertEquals("0001067983", provider.cik("BRK.B"), "la SEC ecrit BRK-B");
        String message = assertThrows(NotFoundException.class, () -> provider.cik("ZZZZ")).getMessage();
        assertTrue(message.contains("ZZZZ"), message);
    }

    @Test
    void withoutUserAgentNothingIsSentAndTheMessageSaysWhatToConfigure() throws Exception {
        SecEdgarProvider unconfigured = new SecEdgarProvider(http, CompanyFactsParserTest.JSON, "  ", clock);
        String message = assertThrows(SourceUnavailableException.class, () -> unconfigured.fetch("EXMP", null))
                .getMessage();
        assertTrue(message.contains("SEC_USER_AGENT"), message);
        assertEquals(message, unconfigured.configurationProblem());
        verify(http, never()).get(anyString(), anyString());
    }

    @Test
    void httpErrorsBecomeClearMessages() throws Exception {
        when(http.get(FACTS_URL, UA)).thenReturn(new Response(403, "Undeclared Automated Tool"));
        assertTrue(fetchError().contains("SEC_USER_AGENT"), "403 : User-Agent refuse");

        when(http.get(FACTS_URL, UA)).thenReturn(new Response(429, ""));
        assertTrue(fetchError().contains("429"));

        when(http.get(FACTS_URL, UA)).thenReturn(new Response(503, ""));
        assertTrue(fetchError().contains("HTTP 503"));

        when(http.get(FACTS_URL, UA)).thenReturn(new Response(200, "<html>maintenance</html>"));
        assertTrue(fetchError().contains("illisible"));
    }

    @Test
    void networkFailuresAndTimeoutsMeanTheSourceIsUnavailable() throws Exception {
        // doThrow : un nouveau when(http.get(...)) declencherait l'exception deja programmee
        doThrow(new HttpTimeoutException("request timed out")).when(http).get(FACTS_URL, UA);
        assertTrue(fetchError().contains("délai d'attente"));

        doThrow(new IOException("Connection refused")).when(http).get(FACTS_URL, UA);
        assertTrue(fetchError().contains("injoignable"));
    }

    @Test
    void aCompanyWithoutXbrlDataIsNotFound() throws Exception {
        when(http.get(FACTS_URL, UA)).thenReturn(new Response(404, ""));
        assertThrows(NotFoundException.class, () -> provider.fetch("EXMP", null));

        when(http.get(FACTS_URL, UA)).thenReturn(new Response(200, "{\"cik\": 1234567, \"entityName\": \"X\", \"facts\": {}}"));
        String message = assertThrows(NotFoundException.class, () -> provider.fetch("EXMP", null)).getMessage();
        assertTrue(message.contains("10-K"), message);
    }

    @Test
    void reportUrlPointsToTheFilingIndex() {
        assertEquals("https://www.sec.gov/Archives/edgar/data/320193/000032019326000026/0000320193-26-000026-index.htm",
                provider.reportUrl("0000320193", "0000320193-26-000026"));
        assertNull(provider.reportUrl("0000320193", null));
    }

    private String fetchError() {
        return assertThrows(SourceUnavailableException.class, () -> provider.fetch("EXMP", "0001234567")).getMessage();
    }
}
