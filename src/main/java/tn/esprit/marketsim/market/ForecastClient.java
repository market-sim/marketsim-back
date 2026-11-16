package tn.esprit.marketsim.market;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.common.ModelUnavailableException;

/**
 * Client HTTP vers le service Python d'ensemble (ARIMA + Holt + naive).
 * Spring Boot reste responsable de la logique métier et de la persistance.
 */
@Slf4j
@Service
public class ForecastClient {

    private final String baseUrl;
    private final int timeoutMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** Moteur local (Java) utilise quand le service Python n'est pas lance : memes modeles, meme contrat JSON. */
    private final LocalForecastEngine local;

    public ForecastClient(@Value("${app.market.forecast.base-url:http://127.0.0.1:8091}") String baseUrl,
                         @Value("${app.market.forecast.timeout-ms:30000}") int timeoutMs,
                         LocalForecastEngine local) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
        this.local = local;
    }

    /**
     * Prevision : service Python s'il repond, sinon moteur local Java (sur les memes donnees reelles). Le champ
     * {@code engine} de la reponse indique lequel a calcule (python / java-local).
     */
    public JsonNode forecast(List<Double> prices, List<String> dates, int horizon, double sentimentScore) {
        try {
            return remote(prices, dates, horizon, sentimentScore);
        } catch (ModelUnavailableException e) {
            log.info("Service de prevision Python indisponible : moteur local Java utilise");
            return local.forecast(prices, horizon, sentimentScore);
        }
    }

    private JsonNode remote(List<Double> prices, List<String> dates, int horizon, double sentimentScore) {
        try {
            StringBuilder sb = new StringBuilder("{\"prices\":[");
            for (int i = 0; i < prices.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"date\":\"").append(dates.get(i)).append("\",\"close\":").append(prices.get(i)).append("}");
            }
            sb.append("],\"horizon\":").append(horizon)
              .append(",\"sentimentScore\":").append(sentimentScore).append("}");
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/forecast"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("forecast service HTTP " + response.statusCode());
            }
            JsonNode node = json.readTree(response.body());
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode o && !o.has("engine")) o.put("engine", "python");
            return node;
        } catch (Exception e) {
            log.warn("Forecast service unavailable: {}", e.getMessage());
            throw new ModelUnavailableException("Service de prévision indisponible : " + e.getMessage(), e);
        }
    }

    /** Toujours vrai : a defaut du service Python, le moteur local Java prend le relais. */
    public boolean isAvailable() {
        return true;
    }

    /** Le service Python d'ensemble repond-il ? */
    public boolean isRemoteAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
