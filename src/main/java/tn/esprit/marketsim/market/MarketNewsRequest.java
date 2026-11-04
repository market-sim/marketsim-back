package tn.esprit.marketsim.market;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Module 5 — corps de la requête POST /api/market/news (ADMIN).
 * Le sentiment n'est jamais fourni par le client ; il est calculé côté serveur.
 */
public record MarketNewsRequest(

    @NotBlank
    String headline,

    @NotBlank
    String source,

    /** URL de l'article (optionnel). */
    String url,

    @NotBlank
    String symbol,

    @NotNull
    Instant publishedAt
) {}
