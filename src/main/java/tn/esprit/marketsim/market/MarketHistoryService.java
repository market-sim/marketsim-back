package tn.esprit.marketsim.market;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.MarketSeries;
import tn.esprit.marketsim.agent.realdata.MarketSeriesRepository;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.common.NotFoundException;

/**
 * Module 5 — Adaptateur pour l'accès aux cours historiques réels.
 *
 * <p>Réutilise les données historiques Yahoo Finance / CSV de {@link MarketDataService}
 * en lecture seule, enrichit la réponse avec le statut de fraîcheur (REAL, CACHED, STALE, UNAVAILABLE),
 * calcule les variations selon la période demandée et garantit qu'aucune donnée synthétique n'est inventée.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketHistoryService {

    private static final Duration FRESH_THRESHOLD = Duration.ofHours(12);

    private final AssetRepository assetRepository;
    private final MarketDataService marketDataService;
    private final MarketSeriesRepository marketSeriesRepository;

    /**
     * Récupère l'historique de cours réels pour le symbole demandé, filtré par période.
     *
     * @param symbol symbole de l'instrument (ex. "AAPL", "BTC/USD")
     * @param period période souhaitée : "1M", "3M", "6M", "1Y", "ALL"
     * @return DTO enrichi avec statut, source, horodatage et points de cours
     */
    public MarketHistoryDto getHistory(String symbol, String period) {
        Asset asset = findAsset(symbol);
        String selectedPeriod = normalizePeriod(period);

        MarketSeries previous = marketSeriesRepository.findBySymbol(asset.getSymbol()).orElse(null);
        PriceSeries series;
        boolean freshFetch = false;

        try {
            series = marketDataService.series(asset.getSymbol());
            MarketSeries current = marketSeriesRepository.findBySymbol(asset.getSymbol()).orElse(null);
            if (current != null && (previous == null || current.getFetchedAt().isAfter(previous.getFetchedAt()))) {
                freshFetch = true;
            }
        } catch (RuntimeException e) {
            log.warn("Données réelles indisponibles pour {} : {}", asset.getSymbol(), e.getMessage());
            if (previous != null) {
                // Utilisation du cache local marqué STALE
                series = PriceSeries.decode(previous.getSymbol(), previous.getSource(), previous.getData());
            } else {
                return MarketHistoryDto.unavailable(
                        asset.getSymbol(),
                        asset.getName(),
                        asset.getType(),
                        "Historique indisponible pour " + asset.getSymbol() + " : source fournisseur injoignable et aucun cache local."
                );
            }
        }

        return buildDto(asset, series, selectedPeriod, freshFetch);
    }

    /**
     * Force le re-téléchargement depuis le fournisseur Yahoo Finance (ADMIN).
     */
    public MarketHistoryDto refresh(String symbol) {
        Asset asset = findAsset(symbol);
        try {
            PriceSeries series = marketDataService.refresh(asset.getSymbol());
            return buildDto(asset, series, "ALL", true);
        } catch (RuntimeException e) {
            log.error("Échec du rafraîchissement forcé pour {} : {}", asset.getSymbol(), e.getMessage());
            MarketSeries cached = marketSeriesRepository.findBySymbol(asset.getSymbol()).orElse(null);
            if (cached != null) {
                PriceSeries series = PriceSeries.decode(cached.getSymbol(), cached.getSource(), cached.getData());
                MarketHistoryDto dto = buildDto(asset, series, "ALL", false);
                return new MarketHistoryDto(
                        dto.symbol(), dto.name(), dto.type(), dto.source(),
                        MarketHistoryDto.DataStatus.STALE,
                        dto.lastUpdated(), dto.from(), dto.to(), dto.points(),
                        dto.firstClose(), dto.lastClose(), dto.changePct(), dto.dataHash(), dto.period(),
                        dto.prices(), "Fournisseur distant injoignable lors du rafraîchissement : cache conservé."
                );
            }
            return MarketHistoryDto.unavailable(
                    asset.getSymbol(),
                    asset.getName(),
                    asset.getType(),
                    "Échec de l'actualisation : " + e.getMessage()
            );
        }
    }

    private MarketHistoryDto buildDto(Asset asset, PriceSeries series, String period, boolean freshFetch) {
        MarketSeries stored = marketSeriesRepository.findBySymbol(asset.getSymbol()).orElse(null);

        MarketHistoryDto.DataStatus status;
        Instant lastUpdated = null;

        if (stored != null && stored.getFetchedAt() != null) {
            lastUpdated = stored.getFetchedAt().atZone(ZoneId.systemDefault()).toInstant();
            boolean withinFreshness = stored.getFetchedAt().isAfter(LocalDateTime.now().minus(FRESH_THRESHOLD));
            if (freshFetch) {
                status = MarketHistoryDto.DataStatus.REAL;
            } else if (withinFreshness || "CSV".equalsIgnoreCase(stored.getSource())) {
                status = MarketHistoryDto.DataStatus.CACHED;
            } else {
                status = MarketHistoryDto.DataStatus.STALE;
            }
        } else {
            status = freshFetch ? MarketHistoryDto.DataStatus.REAL : MarketHistoryDto.DataStatus.CACHED;
            lastUpdated = Instant.now();
        }

        LocalDate[] dates = series.dates();
        double[] closes = series.closes();
        int total = series.size();

        if (total == 0) {
            return MarketHistoryDto.unavailable(asset.getSymbol(), asset.getName(), asset.getType(), "Série vide.");
        }

        LocalDate endDate = dates[total - 1];
        LocalDate startDateCutoff = computeCutoffDate(endDate, period);

        List<MarketHistoryDto.PricePoint> filteredPrices = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (startDateCutoff == null || !dates[i].isBefore(startDateCutoff)) {
                filteredPrices.add(new MarketHistoryDto.PricePoint(dates[i], closes[i]));
            }
        }

        if (filteredPrices.isEmpty()) {
            filteredPrices.add(new MarketHistoryDto.PricePoint(dates[total - 1], closes[total - 1]));
        }

        double firstClose = filteredPrices.get(0).close();
        double lastClose = filteredPrices.get(filteredPrices.size() - 1).close();
        double changePct = firstClose > 0 ? Math.round(((lastClose - firstClose) / firstClose) * 10000.0) / 100.0 : 0.0;

        return new MarketHistoryDto(
                asset.getSymbol(),
                asset.getName(),
                asset.getType(),
                series.source(),
                status,
                lastUpdated,
                filteredPrices.get(0).date(),
                filteredPrices.get(filteredPrices.size() - 1).date(),
                filteredPrices.size(),
                firstClose,
                lastClose,
                changePct,
                series.hash(),
                period,
                filteredPrices,
                null
        );
    }

    private LocalDate computeCutoffDate(LocalDate endDate, String period) {
        return switch (period) {
            case "1M" -> endDate.minusMonths(1);
            case "3M" -> endDate.minusMonths(3);
            case "6M" -> endDate.minusMonths(6);
            case "1Y" -> endDate.minusYears(1);
            default   -> null;
        };
    }

    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) return "ALL";
        String p = period.trim().toUpperCase(Locale.ROOT);
        return switch (p) {
            case "1M", "3M", "6M", "1Y" -> p;
            default -> "ALL";
        };
    }

    private Asset findAsset(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Le symbole est requis.");
        }
        return assetRepository.findAll().stream()
                .filter(a -> a.getSymbol().equalsIgnoreCase(symbol.trim()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Actif introuvable pour le symbole : " + symbol));
    }
}
