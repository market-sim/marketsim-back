package tn.esprit.marketsim.market.provider;

import java.util.List;

/**
 * Interface pour les fournisseurs d'actualités financières externes.
 */
public interface FinancialNewsProvider {
    /** Nom d'identification du fournisseur (ex : "YAHOO_FINANCE", "GOOGLE_NEWS", "FINNHUB"). */
    String getProviderName();

    /** Indique si le fournisseur est configuré et prêt à l'emploi. */
    boolean isAvailable();

    /**
     * Récupère les dernières actualités pour un symbole donné.
     * En cas d'erreur ou d'absence de données, retourne une liste vide sans lever d'exception.
     */
    List<FinancialNewsItem> fetchNews(String symbol);
}
