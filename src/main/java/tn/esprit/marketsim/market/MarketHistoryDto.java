package tn.esprit.marketsim.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Module 5 — Contrat de réponse pour l'historique des cours réels d'un instrument financier.
 * Expose explicitement la provenance, la fraîcheur des données et les horodatages.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketHistoryDto(
        String symbol,
        String name,
        Asset.Type type,
        String source,
        DataStatus status,
        Instant lastUpdated,
        LocalDate from,
        LocalDate to,
        int points,
        Double firstClose,
        Double lastClose,
        Double changePct,
        String dataHash,
        String period,
        List<PricePoint> prices,
        String message
) {

    public enum DataStatus {
        REAL,
        CACHED,
        STALE,
        UNAVAILABLE
    }

    public record PricePoint(LocalDate date, double close) {}

    public static MarketHistoryDto unavailable(String symbol, String name, Asset.Type type, String message) {
        return new MarketHistoryDto(
                symbol,
                name,
                type,
                null,
                DataStatus.UNAVAILABLE,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                "ALL",
                List.of(),
                message != null ? message : "Historique indisponible. Aucune donnée synthétique inventée."
        );
    }
}
