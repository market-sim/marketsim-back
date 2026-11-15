package tn.esprit.marketsim.market.provider;

import java.time.Instant;

/**
 * Article financier unifié extrait d'un fournisseur externe.
 */
public record FinancialNewsItem(
    String headline,
    String source,
    String url,
    String symbol,
    Instant publishedAt,
    String provider
) {}
