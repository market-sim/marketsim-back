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

    @Test
    void reponseDuServicePythonAcceptee() throws Exception {
        // reponse reelle produite par ai-services/finbert_service.py
        var client = new FinBertHttpClient("http://localhost:8090", 1000, "ProsusAI/finbert",
                "b3e74f0635049fa34e60ecd12d49d1717d23821c");
        var r = client.parseAndValidate(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"status": "ANALYZED", "sentimentLabel": "POSITIVE", "positiveProbability": 0.801678,
                 "negativeProbability": 0.065806, "neutralProbability": 0.132516, "sentimentScore": 0.735872,
                 "modelName": "ProsusAI/finbert", "modelVersion": "b3e74f0635049fa34e60ecd12d49d1717d23821c",
                 "analyzedAt": "2026-11-16T10:30:53.439984Z", "errorCode": null}
                """));
        assertEquals(MarketSentimentStatus.ANALYZED, r.status());
        assertEquals(SentimentAnalyzer.Label.POSITIVE, r.sentimentLabel());
        assertEquals(0.735872, r.sentimentScore(), 1e-6);
    }

    @Test
    void autreRevisionGardeeTelleQuelleMaisAutreModeleRefuse() throws Exception {
        var client = new FinBertHttpClient("http://localhost:8090", 1000, "ProsusAI/finbert", "pinned");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String ok = """
                {"status":"ANALYZED","analyzedAt":"2026-01-01T00:00:00Z","sentimentLabel":"NEGATIVE",
                 "positiveProbability":0.1,"negativeProbability":0.7,"neutralProbability":0.2,
                 "sentimentScore":-0.6,"modelName":"ProsusAI/finbert","modelVersion":"main-abc"}
                """;
        var r = client.parseAndValidate(mapper.readTree(ok));
        assertEquals(MarketSentimentStatus.ANALYZED, r.status());
        assertEquals("main-abc", r.modelVersion());
        assertEquals("main-abc", client.modelVersion());
        var other = client.parseAndValidate(mapper.readTree(ok.replace("ProsusAI/finbert", "autre/modele")));
        assertEquals(MarketSentimentStatus.UNAVAILABLE, other.status());
    }
}
