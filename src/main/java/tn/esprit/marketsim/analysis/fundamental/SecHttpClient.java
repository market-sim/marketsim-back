package tn.esprit.marketsim.analysis.fundamental;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

/**
 * Module 3 : requetes HTTP vers la SEC, avec delais d'attente et limite de debit. Isolees dans ce composant pour
 * que les tests le remplacent par un faux : aucun test n'appelle le reseau.
 */
@Component
public class SecHttpClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Le fichier d'une grande societe fait plusieurs Mo. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Reponse brute : code HTTP et contenu. */
    public record Response(int status, String body) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final SecRateLimiter limiter = new SecRateLimiter();

    /**
     * @param userAgent obligatoire pour la SEC : un nom et une adresse de contact
     * @throws IOException          reseau, delai d'attente depasse ({@link java.net.http.HttpTimeoutException})
     * @throws InterruptedException attente interrompue
     */
    public Response get(String url, String userAgent) throws IOException, InterruptedException {
        limiter.acquire();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
