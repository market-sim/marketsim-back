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

    public ForecastClient(@Value("${app.market.forecast.base-url:http://127.0.0.1:8091}") String baseUrl,
                         @Value("${app.market.forecast.timeout-ms:30000}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
    }

    public JsonNode forecast(List<Double> prices, List<String> dates, int horizon, double sentimentScore) {
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
            return json.readTree(response.body());
        } catch (Exception e) {
            log.warn("Forecast service unavailable: {}", e.getMessage());
            throw new ModelUnavailableException("Service de prévision indisponible : " + e.getMessage(), e);
        }
    }

    public boolean isAvailable() {
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
