package tn.esprit.marketsim.market.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Fournisseur optionnel Finnhub.
 *
 * Activé uniquement lorsque la variable d'environnement FINNHUB_API_KEY
 * (ou la propriété app.market.finnhub-api-key) est renseignée.
 * Aucune clé n'est codée en dur.
 */
@Slf4j
@Component
public class FinnhubNewsProvider implements FinancialNewsProvider {

    public static final String PROVIDER_NAME = "FINNHUB";
    private static final String BASE_URL = "https://finnhub.io/api/v1/company-news";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    @Value("${app.market.finnhub-api-key:}")
    private String apiKey;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FinnhubNewsProvider() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /** Constructeur pour injection et tests. */
    public FinnhubNewsProvider(String apiKey, HttpClient httpClient, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public List<FinancialNewsItem> fetchNews(String symbol) {
        if (!isAvailable() || symbol == null || symbol.isBlank()) {
            return List.of();
        }

        String toDate = LocalDate.now().toString();
        String fromDate = LocalDate.now().minusDays(7).toString();
        String url = String.format("%s?symbol=%s&from=%s&to=%s&token=%s",
                BASE_URL, symbol.trim().toUpperCase(), fromDate, toDate, apiKey.trim());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "MarketSim/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                log.warn("Finnhub a répondu HTTP {} pour {}", response.statusCode(), symbol);
                return List.of();
            }

            return parseJson(response.body(), symbol);
        } catch (Exception e) {
            log.warn("Échec de récupération Finnhub pour {} : {}", symbol, e.getMessage());
            return List.of();
        }
    }

    public List<FinancialNewsItem> parseJson(String json, String originalSymbol) {
        List<FinancialNewsItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String headline = node.path("headline").asText(null);
                    String source = node.path("source").asText("Finnhub");
                    String url = node.path("url").asText(null);
                    long epochSeconds = node.path("datetime").asLong(0);

                    if (headline == null || headline.isBlank()) {
                        continue;
                    }

                    Instant publishedAt = epochSeconds > 0
                            ? Instant.ofEpochSecond(epochSeconds)
                            : null;

                    items.add(new FinancialNewsItem(
                            headline.trim(),
                            source.trim(),
                            url != null && !url.isBlank() ? url.trim() : null,
                            originalSymbol.toUpperCase(),
                            publishedAt,
                            PROVIDER_NAME
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Erreur de parsing JSON Finnhub pour {} : {}", originalSymbol, e.getMessage());
        }
        return items;
    }
}
