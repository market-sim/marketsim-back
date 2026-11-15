package tn.esprit.marketsim.market;

import java.time.Instant;
import java.net.URI;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.market.provider.FinancialNewsItem;
import tn.esprit.marketsim.market.provider.FinancialNewsProvider;

/**
 * Module 5 — gestion des actualités financières importées et calcul du sentiment.
 *
 * Le score de sentiment est calculé automatiquement à chaque import via
 * {@link SentimentAnalyzer}. Il n'est jamais fourni ni modifiable par le client.
 */
@Service
@RequiredArgsConstructor
public class MarketNewsService {

    private final MarketNewsRepository repository;
    private final SentimentAnalyzer    analyzer;
    private final List<FinancialNewsProvider> providers;

    @Value("${app.market.news-cache-seconds:300}")
    private long cacheSeconds;

    @Value("${app.market.news-page-max-size:100}")
    private int maxPageSize;

    private final Map<String, Instant> nextFetchBySymbol = new ConcurrentHashMap<>();
    private final Semaphore externalCallSlots = new Semaphore(2);

    /** Toutes les actualités, les plus récentes en premier. */
    public List<MarketNews> findAll() {
        return repository.findAllByOrderByPublishedAtDesc();
    }

    /**
     * Actualités filtrées par symbole d'actif.
     *
     * @param symbol ex : "AAPL", "BTC/USD"
     */
    public List<MarketNews> findBySymbol(String symbol) {
        refreshFromProviders(symbol);
        return repository.findBySymbolIgnoreCaseOrderByPublishedAtDesc(symbol);
    }

    /** Nouveau chemin paginé/filtré ; l'API historique conserve sa liste complète sans paramètres. */
    public List<MarketNews> search(String symbol, Instant from, Instant to, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page doit être positif ou nul");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from doit être antérieur ou égal à to");
        }
        refreshFromProviders(symbol);
        int safeSize = Math.max(1, Math.min(size, Math.max(1, maxPageSize)));
        return repository.search(blankToNull(symbol), from, to,
                PageRequest.of(page, safeSize, Sort.by(Sort.Direction.DESC, "publishedAt")))
                .getContent();
    }

    private void refreshFromProviders(String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        String key = symbol.trim().toUpperCase();
        if (key.length() > 32 || !key.matches("[A-Z0-9^.=/_-]+")) return;
        Instant now = Instant.now();
        if (nextFetchBySymbol.size() > 500) {
            Instant cleanupCutoff = now;
            nextFetchBySymbol.entrySet().removeIf(entry -> entry.getValue().isBefore(cleanupCutoff));
        }
        Instant next = nextFetchBySymbol.get(key);
        if (next != null && now.isBefore(next)) return;
        if (next == null && nextFetchBySymbol.size() >= 1000) return;
        if (!externalCallSlots.tryAcquire()) return;
        try {
            // Re-check après acquisition pour éviter les appels simultanés pour le même symbole.
            next = nextFetchBySymbol.get(key);
            now = Instant.now();
            if (next != null && now.isBefore(next)) return;
            nextFetchBySymbol.put(key, now.plusSeconds(Math.max(30, cacheSeconds)));

            List<FinancialNewsProvider> ordered = providers.stream()
                    .filter(FinancialNewsProvider::isAvailable)
                    .sorted(Comparator.comparingInt(p -> providerRank(p.getProviderName())))
                    .toList();
            for (FinancialNewsProvider provider : ordered) {
                List<FinancialNewsItem> fetched;
                try {
                    fetched = provider.fetchNews(key);
                } catch (RuntimeException ex) {
                    // Les fournisseurs doivent déjà être tolérants, mais une implémentation tierce ne doit
                    // jamais faire échouer la lecture de l'API.
                    continue;
                }
                List<FinancialNewsItem> valid = fetched == null ? List.of() : fetched.stream()
                        .filter(this::isPersistable)
                        .filter(item -> !"GOOGLE_NEWS".equals(item.provider()) || isAuthenticGoogleItem(item))
                        .toList();
                if ("YAHOO_FINANCE".equals(provider.getProviderName()) && !valid.isEmpty()) {
                    for (FinancialNewsItem item : valid) saveProviderItem(item);
                    break;
                }
                for (FinancialNewsItem item : valid) saveProviderItem(item);
                // Google n'est un secours que si Yahoo n'a rien d'exploitable. Finnhub est optionnel
                // et ne remplace pas la décision de secours Google lorsque Yahoo est vide.
            }
        } finally {
            externalCallSlots.release();
        }
    }

    private void saveProviderItem(FinancialNewsItem item) {
        String hash = MarketNews.computeHash(item.url(), item.headline(), item.symbol(), item.publishedAt());
        if (repository.existsByUrlHash(hash)) return;
        double score = analyzer.score(item.headline());
        repository.save(MarketNews.fromProvider(item.headline().trim(), item.source().trim(), item.url().trim(),
                item.symbol(), item.publishedAt(), score, analyzer.label(score), item.provider(), "REAL"));
    }

    private boolean isPersistable(FinancialNewsItem item) {
        return item != null && item.headline() != null && !item.headline().isBlank()
                && item.source() != null && !item.source().isBlank()
                && item.url() != null && validHttpUrl(item.url())
                && item.symbol() != null && !item.symbol().isBlank()
                && item.publishedAt() != null;
    }

    private boolean isAuthenticGoogleItem(FinancialNewsItem item) {
        return isPersistable(item) && !item.source().equalsIgnoreCase("Google News")
                && !item.source().isBlank();
    }

    private boolean validHttpUrl(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception ex) { return false; }
    }

    private int providerRank(String name) {
        return switch (name) { case "YAHOO_FINANCE" -> 0; case "FINNHUB" -> 1; case "GOOGLE_NEWS" -> 2; default -> 3; };
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public MarketNews findOne(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Actualité introuvable"));
    }

    /**
     * Importe un article et calcule automatiquement son score de sentiment.
     *
     * Le client ne fournit jamais sentimentScore ni sentimentLabel ;
     * ceux-ci sont toujours déterminés côté serveur à partir du headline.
     */
    public MarketNews create(MarketNewsRequest req) {
        double score = analyzer.score(req.headline());
        SentimentAnalyzer.Label label = analyzer.label(score);

        MarketNews news = new MarketNews();
        news.setHeadline(req.headline());
        news.setSource(req.source());
        news.setUrl(req.url());
        news.setSymbol(req.symbol().toUpperCase());
        news.setPublishedAt(req.publishedAt());
        news.setSentimentScore(score);
        news.setSentimentLabel(label);
        news.setImportedAt(Instant.now());
        return repository.save(news);
    }

    /** Suppression par id — réservée aux admins (contrôlée dans le controller). */
    public void delete(Long id) {
        repository.delete(findOne(id));
    }
}
