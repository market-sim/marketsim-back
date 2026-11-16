package tn.esprit.marketsim.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class LocalForecastEngineTest {

    private final LocalForecastEngine engine = new LocalForecastEngine();

    private static List<Double> series(int n, double start, double slope, double noise, long seed) {
        Random r = new Random(seed);
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(start + slope * i + r.nextGaussian() * noise);
        return out;
    }

    @Test
    void contratJsonCompatibleAvecPredictionService() {
        JsonNode r = engine.forecast(series(200, 100, 0.2, 0.5, 1), 5, 0);
        assertEquals("ANALYZED", r.path("status").asText());
        assertEquals(5, r.path("forecast").size());
        assertTrue(r.path("forecast").get(4).path("value").asDouble() > 0);
        assertTrue(r.path("metrics").has("mae") && r.path("metrics").has("rmse"));
        assertTrue(r.path("residuals").size() > 0);
        assertEquals(4, r.path("candidates").size());
        assertEquals("java-local", r.path("engine").asText());
    }

    @Test
    void uneTendanceNetteEstSuivie() {
        JsonNode r = engine.forecast(series(200, 100, 0.5, 0.05, 2), 10, 0);
        double last = 100 + 0.5 * 199;
        double f10 = r.path("forecast").get(9).path("value").asDouble();
        assertTrue(f10 > last + 3, "la prevision doit prolonger la hausse : " + f10);
        assertTrue(!"naive".equals(r.path("selectedModel").asText()));
    }

    @Test
    void leSentimentInflechitLegerementLaPrevision() {
        List<Double> s = series(150, 50, 0, 1, 3);
        double neutral = engine.forecast(s, 5, 0).path("forecast").get(4).path("value").asDouble();
        JsonNode pos = engine.forecast(s, 5, 1);
        double positive = pos.path("forecast").get(4).path("value").asDouble();
        assertTrue(positive > neutral);
        assertTrue(Math.abs(pos.path("sentimentContribution").asDouble()) < 5, "contribution bornee");
    }

    @Test
    void historiqueTropCourt() {
        assertEquals("INSUFFICIENT_DATA", engine.forecast(series(10, 1, 0, 0, 4), 5, 0).path("status").asText());
    }
}
