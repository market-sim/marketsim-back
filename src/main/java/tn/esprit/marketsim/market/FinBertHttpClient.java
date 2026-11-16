package tn.esprit.marketsim.market;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Client REST interne vers le service FinBERT local. Aucun secret ne transite vers React. */
@Component
public class FinBertHttpClient implements FinancialNlpClient {

    private final String endpoint;
    private final String expectedModelName;
    private final String expectedModelVersion;
    /** Revision reellement servie par le service FinBERT si elle differe de la configuration. */
    private volatile String servedVersion;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FinBertHttpClient(
            @Value("${app.market.nlp.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${app.market.nlp.timeout-ms:20000}") long timeoutMs,
            @Value("${app.market.finbert.model-name:ProsusAI/finbert}") String modelName,
            @Value("${app.market.finbert.revision:b3e74f0635049fa34e60ecd12d49d1717d23821c}") String modelVersion) {
        this.endpoint = trimTrailingSlash(baseUrl) + "/internal/v1/analyze";
        this.expectedModelName = modelName;
        this.expectedModelVersion = modelVersion;
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public String modelVersion() {
        return servedVersion != null ? servedVersion : expectedModelVersion;
    }

    @Override
    public NlpResult analyze(String text) {
        try {
            String body = objectMapper.writeValueAsString(new AnalyzeRequest(text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return NlpResult.unavailable("NLP_HTTP_" + response.statusCode());
            }
            return parseAndValidate(objectMapper.readTree(response.body()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return NlpResult.unavailable("NLP_REQUEST_INTERRUPTED");
        } catch (Exception ex) {
            return NlpResult.unavailable("NLP_SERVICE_UNREACHABLE");
        }
    }

    NlpResult parseAndValidate(JsonNode body) {
        try {
            MarketSentimentStatus status = MarketSentimentStatus.valueOf(
                    requiredText(body, "status").toUpperCase(Locale.ROOT));
            Instant analyzedAt = Instant.parse(requiredText(body, "analyzedAt"));
            String errorCode = optionalText(body, "errorCode");

            if (status != MarketSentimentStatus.ANALYZED) {
                return new NlpResult(status, null, null, null, null, null,
                        optionalText(body, "modelName"), optionalText(body, "modelVersion"), analyzedAt, errorCode);
            }

            String rawLabel = requiredText(body, "sentimentLabel").toUpperCase(Locale.ROOT);
            SentimentAnalyzer.Label label = SentimentAnalyzer.Label.valueOf(rawLabel);
            double positive = requiredNumber(body, "positiveProbability");
            double negative = requiredNumber(body, "negativeProbability");
            double neutral = requiredNumber(body, "neutralProbability");
            double score = requiredNumber(body, "sentimentScore");
            validateProbabilities(positive, negative, neutral);
            if (Math.abs(score - (positive - negative)) > 0.01) {
                throw new IllegalArgumentException("Inconsistent sentiment score");
            }
            // Le modele doit etre FinBERT. La revision attendue est celle de la configuration ; si le service a du
            // charger une autre revision (revision epinglee introuvable sur Hugging Face), on garde la VRAIE
            // revision utilisee, pour que la tracabilite reste exacte.
            if (!expectedModelName.equals(requiredText(body, "modelName"))) {
                throw new IllegalArgumentException("Unexpected model identity");
            }
            String version = requiredText(body, "modelVersion");
            if (!expectedModelVersion.equals(version)) servedVersion = version;
            return new NlpResult(status, label, positive, negative, neutral, positive - negative,
                    expectedModelName, version, analyzedAt, null);
        } catch (Exception ex) {
            return NlpResult.unavailable("NLP_INVALID_RESPONSE");
        }
    }

    static void validateProbabilities(double positive, double negative, double neutral) {
        if (!validProbability(positive) || !validProbability(negative) || !validProbability(neutral)) {
            throw new IllegalArgumentException("Probabilities must be finite values in [0, 1]");
        }
        if (Math.abs(positive + negative + neutral - 1.0) > 0.01) {
            throw new IllegalArgumentException("Probabilities must sum to 1");
        }
    }

    private static boolean validProbability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static String requiredText(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return node.asText();
    }

    private static String optionalText(JsonNode body, String name) {
        JsonNode node = body.get(name);
        return node == null || node.isNull() || !node.isTextual() ? null : node.asText();
    }

    private static double requiredNumber(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || !node.isNumber()) throw new IllegalArgumentException("Missing " + name);
        return node.doubleValue();
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private record AnalyzeRequest(String text) {}
}
