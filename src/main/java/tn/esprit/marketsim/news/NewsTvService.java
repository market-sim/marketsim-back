package tn.esprit.marketsim.news;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Chaines d'information BOURSIERE diffusees en direct sur YouTube (Bloomberg, Yahoo Finance, Asharq Bloomberg,
 * Al-Arabiya Business). Le serveur ne retransmet aucune video : il repere seulement, toutes les 5 minutes, quelle
 * diffusion est en cours et si elle autorise l'integration (oEmbed) ; le navigateur affiche ensuite le lecteur YouTube officiel.
 * Si le reperage echoue, la chaine reste proposee et le frontend utilise l'adresse « live_stream?channel= » de YouTube.
 */
@Slf4j
@Service
public class NewsTvService {

    private record Channel(String id, String name, String language, String handle, String channelId) {
    }

    /**
     * @param videoId diffusion en cours verifiee et integrable ; nul si non repere
     * @param live    vrai si une diffusion en direct integrable a ete trouvee
     */
    public record TvChannel(String id, String name, String language, String channelId, String videoId, boolean live,
                            String watchUrl) {
    }

    /** Ordre de presentation : l'anglais d'abord (Bloomberg, Yahoo Finance), puis l'arabe. */
    private static final List<Channel> CHANNELS = List.of(
            new Channel("BLOOMBERG", "Bloomberg TV", "EN", "markets", "UCIALMKvObZNtJ6AmdCLP7Lg"),
            new Channel("YAHOO", "Yahoo Finance", "EN", "YahooFinance", "UCEAZeUIeJs0IjQiqTCdVSIg"),
            new Channel("ASHARQ", "الشرق بلومبرغ · Asharq Bloomberg", "AR", "asharqbusiness", "UCxjpGbfoLy6oodYdiyzQE4g"),
            new Channel("ALARABIYA", "العربية Business · Al Arabiya", "AR", "AlArabiyaBusiness", "UC0mthbFRIm_uYkkzMX09sJw"));

    /** Une liste plus vieille que cela n'est plus servie : on la recalcule tout de suite. */
    static final Duration MAX_STALE = Duration.ofMinutes(15);
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern VIDEO_DETAILS = Pattern.compile("\"videoDetails\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"");

    private record Cached(List<TvChannel> channels, Instant at) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Cached cache;

    /**
     * Liste prete a servir : les pages YouTube sont lourdes (premier calcul ~15 s), donc elle est preparee au demarrage
     * puis rafraichie toutes les 5 minutes en arriere-plan ; l'utilisateur ne l'attend jamais.
     */
    public List<TvChannel> channels() {
        Cached c = cache;
        if (c != null && c.at().plus(MAX_STALE).isAfter(Instant.now())) return c.channels();
        synchronized (this) {
            if (cache != null && cache.at().plus(MAX_STALE).isAfter(Instant.now())) return cache.channels();
            return compute();
        }
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT3S")
    public void refresh() {
        try {
            compute();
        } catch (RuntimeException e) {
            log.warn("TV : rafraichissement impossible ({})", e.getMessage());
        }
    }

    private List<TvChannel> compute() {
        List<CompletableFuture<TvChannel>> calls = new ArrayList<>();
        for (Channel ch : CHANNELS) calls.add(CompletableFuture.supplyAsync(() -> check(ch)));
        List<TvChannel> out = calls.stream().map(CompletableFuture::join).toList();
        cache = new Cached(out, Instant.now());
        return out;
    }

    private TvChannel check(Channel ch) {
        String watch = "https://www.youtube.com/channel/" + ch.channelId() + "/live";
        try {
            Optional<String> id = parseLiveVideoId(fetch("https://www.youtube.com/@" + ch.handle() + "/live"), ch.channelId());
            if (id.isPresent() && embeddable(id.get())) {
                return new TvChannel(ch.id(), ch.name(), ch.language(), ch.channelId(), id.get(), true, "https://www.youtube.com/watch?v=" + id.get());
            }
        } catch (Exception e) {
            log.warn("TV {} : reperage impossible ({})", ch.id(), e.getMessage());
            // reperage en echec : on garde la chaine, le navigateur essaiera l'adresse live_stream de YouTube
            return new TvChannel(ch.id(), ch.name(), ch.language(), ch.channelId(), null, true, watch);
        }
        return new TvChannel(ch.id(), ch.name(), ch.language(), ch.channelId(), null, false, watch);
    }

    /**
     * Identifiant de la diffusion EN DIRECT de la chaine, lu dans sa page {@code /live} : le bloc {@code videoDetails}
     * doit etre un direct ({@code "isLive":true}) ET appartenir a la bonne chaine (evite de prendre une video quelconque
     * ou un direct d'une autre chaine suggere sur la page).
     */
    static Optional<String> parseLiveVideoId(String html, String expectedChannelId) {
        if (html == null) return Optional.empty();
        Matcher m = VIDEO_DETAILS.matcher(html);
        while (m.find()) {
            // la fenetre s'arrete au bloc videoDetails suivant : on ne doit pas lire les champs d'un autre lecteur
            int next = html.indexOf("\"videoDetails\":{\"videoId\"", m.end());
            int end = Math.min(next < 0 ? html.length() : next, m.start() + 4000);
            String window = html.substring(m.start(), end);
            if (window.contains("\"isLive\":true") && window.contains("\"channelId\":\"" + expectedChannelId + "\"")) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    private boolean embeddable(String videoId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v="
                + videoId + "&format=json")).timeout(TIMEOUT).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    private String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body();
    }
}
