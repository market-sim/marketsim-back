package tn.esprit.marketsim.market;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;

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
        return repository.findBySymbolIgnoreCaseOrderByPublishedAtDesc(symbol);
    }

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
