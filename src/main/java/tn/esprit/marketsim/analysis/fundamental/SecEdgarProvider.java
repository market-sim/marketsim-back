package tn.esprit.marketsim.analysis.fundamental;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.analysis.fundamental.XbrlInstanceParser.InstanceShares;
import tn.esprit.marketsim.common.NotFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Module 3 : donnees fondamentales gratuites et sans cle de la SEC americaine (EDGAR, rapports XBRL).
 *
 * <ol>
 *   <li>Symbole -> CIK (identifiant SEC de la societe) grace a {@value #TICKERS_URL}. Ce fichier (environ 1 Mo)
 *       est garde en memoire {@link #TICKERS_FRESH_FOR} ; le CIK est en plus garde avec les donnees en base.</li>
 *   <li>Faits XBRL de la societe : {@value #FACTS_URL}, lus par {@link CompanyFactsParser}.</li>
 *   <li>Seulement pour une societe a plusieurs categories d'actions (Alphabet, Meta...) : liste des fichiers du
 *       dernier 10-K ({@code index.json}), puis son instance XBRL ({@code *_htm.xml}, 2 a 3 Mo), lue par
 *       {@link XbrlInstanceParser} pour additionner les categories. Si elle est illisible ou injoignable, l'analyse
 *       continue sans elle (nombre d'actions a la cloture, ou « non disponible »).</li>
 * </ol>
 *
 * <p>La SEC exige dans chaque requete un User-Agent avec un nom et une adresse de contact. Il est lu dans
 * {@code app.analysis.sec.user-agent}, alimentee par la variable d'environnement {@code SEC_USER_AGENT} : aucune
 * adresse n'est ecrite dans le code ni dans Git.
 */
@Slf4j
@Component
public class SecEdgarProvider implements FundamentalsProvider {

    public static final String SOURCE = "SEC_EDGAR";
    static final String TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";
    static final String FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK%s.json";
    /** Dossier d'un depot : CIK sans les zeros de tete, numero de depot sans tirets. */
    static final String FILING_FOLDER = "https://www.sec.gov/Archives/edgar/data/%d/%s/";
    static final Duration TICKERS_FRESH_FOR = Duration.ofHours(24);

    static final String MISSING_USER_AGENT = "La source SEC EDGAR n'est pas configurée sur le serveur. La SEC exige "
            + "un nom et une adresse e-mail de contact dans chaque requête : définissez la variable d'environnement "
            + "SEC_USER_AGENT (par exemple « MarketSim contact@exemple.com »), puis redémarrez le back.";

    private final SecHttpClient http;
    private final JsonMapper json;
    private final String userAgent;
    private final Clock clock;

    /** Correspondance symbole -> CIK et date de son telechargement. */
    private Map<String, String> tickers;
    private Instant tickersLoadedAt;

    @Autowired
    public SecEdgarProvider(SecHttpClient http, JsonMapper json,
                            @Value("${app.analysis.sec.user-agent:}") String userAgent) {
        this(http, json, userAgent, Clock.systemUTC());
    }

    SecEdgarProvider(SecHttpClient http, JsonMapper json, String userAgent, Clock clock) {
        this.http = http;
        this.json = json;
        this.userAgent = userAgent == null ? "" : userAgent.trim();
        this.clock = clock;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String configurationProblem() {
        return userAgent.isEmpty() ? MISSING_USER_AGENT : null;
    }

    @Override
    public FundamentalsData fetch(String symbol, String knownCik) {
        if (configurationProblem() != null) {
            throw new SourceUnavailableException(MISSING_USER_AGENT);
        }
        String cik = knownCik != null ? knownCik : cik(symbol);
        SecHttpClient.Response r = get(String.format(FACTS_URL, cik));
        if (r.status() == 404) {
            throw new NotFoundException("La SEC ne publie pas de données financières structurées (XBRL) pour " + symbol);
        }
        checkStatus(r);
        FundamentalsData data = CompanyFactsParser.parse(read(r.body()));
        if (data.years().isEmpty()) {
            throw new NotFoundException("Aucun rapport annuel 10-K exploitable à la SEC pour " + symbol
                    + " (une société étrangère publie un 20-F, non pris en charge)");
        }
        if (CompanyFactsParser.needsInstance(data)) {
            String accession = data.years().get(0).accession();
            try {
                data = CompanyFactsParser.withInstance(data, instanceShares(cik, accession));
            } catch (SourceUnavailableException | IllegalArgumentException e) {
                // le reste de l'analyse est valable : on ne la refuse pas pour le seul nombre d'actions
                log.warn("Fondamentaux {} : instance XBRL du depot {} non lue ({})", symbol, accession, e.getMessage());
            }
        }
        return data;
    }

    // ===================== Instance XBRL du dernier 10-K =====================

    /** Nombres d'actions lus dans l'instance XBRL d'un depot (2 requetes : liste des fichiers, puis l'instance). */
    InstanceShares instanceShares(String cik, String accession) {
        String folder = String.format(FILING_FOLDER, Long.parseLong(cik), accession.replace("-", ""));
        SecHttpClient.Response index = get(folder + "index.json");
        checkStatus(index);
        String name = instanceFileName(read(index.body()));
        if (name == null) {
            throw new SourceUnavailableException("Aucune instance XBRL dans le dépôt " + accession + ".");
        }
        SecHttpClient.Response instance = get(folder + name);
        checkStatus(instance);
        return XbrlInstanceParser.parse(instance.body());
    }

    /**
     * Nom de l'instance XBRL dans la liste des fichiers d'un depot. Un 10-K « inline XBRL » (la regle depuis 2019 a
     * 2021) en contient une, extraite par la SEC, nommee {@code <prefixe>_htm.xml} ; null s'il n'y en a pas.
     */
    static String instanceFileName(JsonNode index) {
        for (JsonNode item : index.path("directory").path("item").values()) {
            String name = item.path("name").asString("");
            if (name.endsWith("_htm.xml")) return name;
        }
        return null;
    }

    /** Page du depot 10-K sur EDGAR, ex. .../data/320193/000032019326000026/0000320193-26-000026-index.htm */
    @Override
    public String reportUrl(String cik, String accession) {
        if (cik == null || accession == null || accession.isBlank()) return null;
        return "https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(cik) + "/" + accession.replace("-", "")
                + "/" + accession + "-index.htm";
    }

    // ===================== Symbole -> CIK =====================

    /** CIK sur 10 chiffres. La SEC ecrit BRK-B la ou d'autres ecrivent BRK.B. */
    String cik(String symbol) {
        String ticker = symbol.trim().toUpperCase(Locale.ROOT).replace('.', '-');
        String cik = tickers().get(ticker);
        if (cik == null) {
            throw new NotFoundException("Aucune société cotée aux États-Unis ne porte le symbole " + symbol
                    + " dans la liste de la SEC");
        }
        return cik;
    }

    private synchronized Map<String, String> tickers() {
        Instant now = clock.instant();
        if (tickers == null || tickersLoadedAt.plus(TICKERS_FRESH_FOR).isBefore(now)) {
            SecHttpClient.Response r = get(TICKERS_URL);
            checkStatus(r);
            tickers = parseTickers(read(r.body()));
            tickersLoadedAt = now;
        }
        return tickers;
    }

    /** {@code {"0": {"cik_str": 320193, "ticker": "AAPL", "title": "Apple Inc."}, ...}} -> {@code AAPL -> 0000320193}. */
    static Map<String, String> parseTickers(JsonNode root) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode row : root.values()) {
            String ticker = row.path("ticker").asString("").toUpperCase(Locale.ROOT);
            if (!ticker.isEmpty() && row.path("cik_str").isNumber()) {
                out.putIfAbsent(ticker, String.format("%010d", row.path("cik_str").asLong()));
            }
        }
        return out;
    }

    // ===================== HTTP =====================

    private SecHttpClient.Response get(String url) {
        try {
            return http.get(url, userAgent);
        } catch (HttpTimeoutException e) {
            throw new SourceUnavailableException("La SEC n'a pas répondu à temps (délai d'attente dépassé).");
        } catch (IOException e) {
            throw new SourceUnavailableException("SEC EDGAR est injoignable (" + e.getMessage() + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailableException("Le téléchargement depuis la SEC a été interrompu.");
        }
    }

    private static void checkStatus(SecHttpClient.Response r) {
        if (r.status() == 200) return;
        if (r.status() == 403) {
            throw new SourceUnavailableException("La SEC a refusé la requête (HTTP 403). Vérifiez SEC_USER_AGENT : "
                    + "il doit contenir un nom et une adresse e-mail de contact.");
        }
        if (r.status() == 429) {
            throw new SourceUnavailableException("La SEC limite le nombre de requêtes (HTTP 429) : réessayez dans "
                    + "quelques minutes.");
        }
        throw new SourceUnavailableException("SEC EDGAR a répondu par une erreur (HTTP " + r.status() + ").");
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new SourceUnavailableException("La réponse de la SEC est illisible (JSON invalide).");
        }
    }
}
