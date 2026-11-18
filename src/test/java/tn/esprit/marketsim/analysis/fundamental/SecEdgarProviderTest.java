package tn.esprit.marketsim.analysis.fundamental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
