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
    void leSentimentEstUnCandidatEtSaContributionEstExpliquee() {
        List<Double> s = series(150, 50, 0, 1, 3);
        JsonNode neutre = engine.forecast(s, 5, 0);
        JsonNode positif = engine.forecast(s, 5, 1);
        JsonNode negatif = engine.forecast(s, 5, -1);
        assertEquals(0.0, neutre.path("sentimentContribution").asDouble(), 1e-9);
        assertTrue(positif.path("sentimentContribution").asDouble() > 0, "un sentiment positif pousse la cible vers le haut");
        assertTrue(negatif.path("sentimentContribution").asDouble() < 0);
        boolean present = false;
        for (JsonNode c : positif.path("candidates")) present |= "sentiment_adjusted".equals(c.path("name").asText());
        assertTrue(present);
        assertTrue(positif.path("modelVersion").asText().endsWith("-ensemble-v1"));
    }

    @Test
    void historiqueTropCourt() {
        assertEquals("INSUFFICIENT_DATA", engine.forecast(series(10, 1, 0, 0, 4), 5, 0).path("status").asText());
    }

    @Test
    void mapeCalculeeSurLesMemesSeancesQueLesResidus() {
        // serie qui part de 1 et monte a 100 : l'ancien calcul (residus / premiers prix) donnait des MAPE enormes
        List<Double> s = new ArrayList<>();
        for (int i = 0; i < 200; i++) s.add(1 + i * 0.5 + Math.sin(i) * 0.3);
        double mape = engine.forecast(s, 5, 0).path("metrics").path("mape").asDouble();
        assertTrue(mape > 0 && mape < 5, "MAPE realiste : " + mape);
    }

    @Test
    void confianceDuSensEntre50Et95() {
        assertEquals(0.5, PredictionService.directionConfidence(100, 100, 2.0, 5), 1e-9);
        double faible = PredictionService.directionConfidence(100, 100.5, 2.0, 5);
        double forte = PredictionService.directionConfidence(100, 110, 2.0, 5);
        assertTrue(faible > 0.5 && faible < 0.6);
        assertTrue(forte > 0.9 && forte <= 0.95);
    }
}
