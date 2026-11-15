package tn.esprit.marketsim.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FinBertHttpClientTest {

    @Test
    void validProbabilitiesAreAccepted() {
        FinBertHttpClient.validateProbabilities(.7, .2, .1);
    }

    @Test
    void outOfRangeNonFiniteAndNonSummingProbabilitiesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FinBertHttpClient.validateProbabilities(1.1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FinBertHttpClient.validateProbabilities(Double.NaN, .2, .8));
        assertThrows(IllegalArgumentException.class,
                () -> FinBertHttpClient.validateProbabilities(.2, .2, .2));
    }

    @Test
    void invalidModelLabelIsRepresentedAsUnavailable() throws Exception {
        var client = new FinBertHttpClient("http://localhost:8090", 1000,
                "ProsusAI/finbert", "v1");
        var response = client.parseAndValidate(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"status":"ANALYZED","analyzedAt":"2026-01-01T00:00:00Z","sentimentLabel":"BUY",
                 "positiveProbability":0.7,"negativeProbability":0.2,"neutralProbability":0.1,
                 "sentimentScore":0.5,"modelName":"ProsusAI/finbert","modelVersion":"v1"}
                """));
        assertEquals(MarketSentimentStatus.UNAVAILABLE, response.status());
    }
}
