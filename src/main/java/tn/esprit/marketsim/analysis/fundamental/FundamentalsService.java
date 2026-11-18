package tn.esprit.marketsim.analysis.fundamental;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.fundamental.FundamentalRatios.Benchmark;
import tn.esprit.marketsim.analysis.fundamental.FundamentalRatios.RatioValue;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.FiscalYear;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.DataStatus;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.Detail;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.ItemView;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.Overview;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.RatioView;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.Summary;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.YearView;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.market.AssetRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Module 3 : analyse fondamentale des actions de la plateforme (actifs STOCK du module 5).
 *
 * <h2>Cache</h2>
 * <ul>
 *   <li>Donnees de moins de {@value #REFRESH_AFTER_DAYS} jours : utilisees telles quelles.</li>
 *   <li>Plus anciennes, ou absentes : telechargement depuis la source ({@link FundamentalsProvider}).</li>
 *   <li>Source injoignable : le cache est utilise meme ancien, avec un avertissement ; sans cache, HTTP 503.</li>
 * </ul>
 * Le cache est commun a tous les utilisateurs. Deux demandes simultanees pour la meme action ne declenchent qu'un
 * telechargement (verrou par symbole).
 *
 * <p>Le cours vient de {@link MarketDataService} (module 6, derniere cloture connue).
 */
@Slf4j
@Service
public class FundamentalsService {

    public static final int REFRESH_AFTER_DAYS = 30;
    static final Duration REFRESH_AFTER = Duration.ofDays(REFRESH_AFTER_DAYS);
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CompanyFundamentalsRepository repository;
    private final AssetRepository assets;
    private final MarketDataService marketData;
    private final FundamentalsProvider provider;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public FundamentalsService(CompanyFundamentalsRepository repository, AssetRepository assets,
                               MarketDataService marketData, FundamentalsProvider provider, JsonMapper json) {
        this(repository, assets, marketData, provider, json, Clock.systemDefaultZone());
    }

    FundamentalsService(CompanyFundamentalsRepository repository, AssetRepository assets,
                        MarketDataService marketData, FundamentalsProvider provider, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.assets = assets;
        this.marketData = marketData;
        this.provider = provider;
        this.json = json;
        this.clock = clock;
    }

    /** Ce qu'il faut pour calculer et afficher les ratios d'une action. */
    private record Snapshot(Asset asset, CompanyFundamentals row, FundamentalsData data, Double price,
                            LocalDate priceDate, List<RatioValue> ratios) {
    }

    // ===================== Lire =====================

    /** Toutes les actions et l'etat de leurs donnees. N'appelle jamais la source : seulement le cache. */
    public Overview overview() {
        List<Asset> stocks = stocks();
        Map<String, Snapshot> snapshots = snapshots(stocks, null);
        Map<Ratio, Map<String, Benchmark>> benchmarks = benchmarks(snapshots);
        List<Summary> rows = new ArrayList<>();
        for (Asset a : stocks) {
            Snapshot s = snapshots.get(a.getSymbol());
            if (s == null) {
                rows.add(new Summary(a.getSymbol(), a.getName(), DataStatus.MISSING, null, null, null, List.of()));
                continue;
            }
            FiscalYear latest = s.data().years().get(0);
            rows.add(new Summary(a.getSymbol(), a.getName(), isOld(s.row()) ? DataStatus.STALE : DataStatus.FRESH,
                    s.row().getDownloadedAt(), latest.fiscalYear(), latest.end(), ratioViews(s, benchmarks)));
        }
        return new Overview(provider.source(), REFRESH_AFTER_DAYS, provider.configurationProblem(), rows);
    }

    /** Analyse d'une action : cache recent, sinon telechargement, sinon cache ancien avec avertissement, sinon 503. */
    public Detail get(String symbol) {
        Asset asset = stock(symbol);
        CompanyFundamentals row;
        String warning = null;
        synchronized (lock(asset.getSymbol())) {
            row = repository.findBySymbol(asset.getSymbol()).orElse(null);
            if (row == null || isOld(row)) {
                try {
                    row = download(asset, row);
                } catch (SourceUnavailableException e) {
                    if (row == null) {
                        throw new FundamentalsUnavailableException("Données fondamentales de " + asset.getSymbol()
                                + " indisponibles : aucune donnée en cache et la source n'a pas pu être consultée. "
                                + e.getMessage());
                    }
                    log.warn("Fondamentaux {} : mise a jour impossible ({}), cache du {} utilise",
                            asset.getSymbol(), e.getMessage(), row.getDownloadedAt());
                    warning = staleWarning(row, e.getMessage());
                }
            }
        }
        return detail(asset, row, warning);
    }

    /** Rafraichissement force (administrateur) : telecharge meme si le cache est recent. */
    public Detail refresh(String symbol) {
        Asset asset = stock(symbol);
        CompanyFundamentals row;
        synchronized (lock(asset.getSymbol())) {
            CompanyFundamentals cached = repository.findBySymbol(asset.getSymbol()).orElse(null);
            try {
                row = download(asset, cached);
            } catch (SourceUnavailableException e) {
                throw new FundamentalsUnavailableException("Rafraîchissement de " + asset.getSymbol()
                        + " impossible. " + e.getMessage()
                        + (cached == null ? "" : " Les données en cache du "
                        + cached.getDownloadedAt().format(FR_DATE) + " restent utilisées."));
            }
        }
        return detail(asset, row, null);
    }

    // ===================== Cache et source =====================

    private CompanyFundamentals download(Asset asset, CompanyFundamentals cached) {
        FundamentalsData data = provider.fetch(asset.getSymbol(), cached == null ? null : cached.getCik());
        CompanyFundamentals row = cached != null ? cached : new CompanyFundamentals();
        row.setSymbol(asset.getSymbol());
        row.setCik(data.cik());
        row.setCompanyName(data.companyName());
        row.setSource(provider.source());
        row.setDownloadedAt(LocalDateTime.now(clock));
        row.setDataJson(json.writeValueAsString(data));
        repository.save(row);
        return row;
    }

    /** Plus de 30 jours : a retelecharger. */
    boolean isOld(CompanyFundamentals row) {
        return !row.getDownloadedAt().isAfter(LocalDateTime.now(clock).minus(REFRESH_AFTER));
    }

    private String staleWarning(CompanyFundamentals row, String reason) {
        long days = Duration.between(row.getDownloadedAt(), LocalDateTime.now(clock)).toDays();
        return "Données en cache téléchargées le " + row.getDownloadedAt().format(FR_DATE) + " (il y a " + days
                + " jours) : la mise à jour a échoué. " + reason
                + " Un rapport annuel plus récent est peut-être paru depuis.";
    }

    private Object lock(String symbol) {
        return locks.computeIfAbsent(symbol, k -> new Object());
    }

    // ===================== Actions de la plateforme =====================

    /** Actifs de type action du module 5, dans l'ordre de la base. */
    private List<Asset> stocks() {
        return assets.findAll().stream().filter(a -> a.getType() == Asset.Type.STOCK).toList();
    }

    /** L'action demandee : 404 si l'actif n'existe pas, 400 si ce n'est pas une action. */
    Asset stock(String symbol) {
        Asset asset = assets.findAll().stream()
                .filter(a -> a.getSymbol().equalsIgnoreCase(symbol.trim()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Actif inconnu : " + symbol));
        if (asset.getType() != Asset.Type.STOCK) {
            throw new IllegalArgumentException("L'analyse fondamentale ne concerne que les actions : "
                    + asset.getSymbol() + " est " + typeLabel(asset.getType()) + ".");
        }
        return asset;
    }

    private static String typeLabel(Asset.Type type) {
        return switch (type) {
            case FOREX -> "une paire de devises";
            case CRYPTO -> "une cryptomonnaie";
            case COMMODITY -> "une matière première";
            case BOND -> "une obligation";
            case STOCK -> "une action";
        };
    }

    // ===================== Ratios et repere =====================

    /**
     * Donnees, cours et ratios de chaque action en cache. {@code current} (facultatif) remplace la ligne lue en base
     * pour son action (celle qu'on vient de telecharger ou de choisir).
     */
    private Map<String, Snapshot> snapshots(List<Asset> stocks, CompanyFundamentals current) {
        Map<String, CompanyFundamentals> rows = new HashMap<>();
        for (CompanyFundamentals r : repository.findAll()) rows.put(r.getSymbol(), r);
        if (current != null) rows.put(current.getSymbol(), current);

        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (Asset a : stocks) {
            CompanyFundamentals row = rows.get(a.getSymbol());
            if (row == null) continue;
            FundamentalsData data = json.readValue(row.getDataJson(), FundamentalsData.class);
            if (data.years().isEmpty()) continue;
            PriceSeries series = price(a.getSymbol());
            Double price = series == null ? null : series.closes()[series.size() - 1];
            LocalDate priceDate = series == null ? null : series.dates()[series.size() - 1];
            List<FiscalYear> years = data.years();
            List<RatioValue> ratios = FundamentalRatios.compute(years.get(0), years.size() > 1 ? years.get(1) : null, price);
            out.put(a.getSymbol(), new Snapshot(a, row, data, price, priceDate, ratios));
        }
        return out;
    }

    /** Derniere cloture connue, ou null : sans cours, seuls les ratios qui en dependent manquent. */
    private PriceSeries price(String symbol) {
        try {
            PriceSeries s = marketData.series(symbol);
            return s.size() == 0 ? null : s;
        } catch (RuntimeException e) {
            log.warn("Fondamentaux {} : cours indisponible ({})", symbol, e.getMessage());
            return null;
        }
    }

    /** Pour chaque ratio : le repere de chaque action parmi les actions en cache. */
    private static Map<Ratio, Map<String, Benchmark>> benchmarks(Map<String, Snapshot> snapshots) {
        Map<Ratio, Map<String, Benchmark>> out = new EnumMap<>(Ratio.class);
        for (Ratio ratio : Ratio.values()) {
            Map<String, Double> values = new HashMap<>();
            for (Snapshot s : snapshots.values()) {
                s.ratios().stream().filter(r -> r.ratio() == ratio).findFirst()
                        .ifPresent(r -> values.put(s.asset().getSymbol(), r.value()));
            }
            out.put(ratio, FundamentalRatios.benchmark(values));
        }
        return out;
    }

    private static List<RatioView> ratioViews(Snapshot s, Map<Ratio, Map<String, Benchmark>> benchmarks) {
        return s.ratios().stream().map(r -> {
            Benchmark b = benchmarks.get(r.ratio()).get(s.asset().getSymbol());
            Ratio ratio = r.ratio();
            return new RatioView(ratio, ratio.label(), ratio.unit(), ratio.formula(), ratio.explanation(), r.value(),
                    r.unavailableReason(), b == null ? null : b.level(), b == null ? 0 : b.compared());
        }).toList();
    }

    // ===================== Reponse =====================

    private Detail detail(Asset asset, CompanyFundamentals row, String warning) {
        Map<String, Snapshot> snapshots = snapshots(stocks(), row);
        Snapshot s = snapshots.get(asset.getSymbol());
        if (s == null) {
            throw new NotFoundException("Aucun exercice annuel exploitable pour " + asset.getSymbol());
        }
        List<YearView> years = s.data().years().stream().map(y -> new YearView(y.fiscalYear(), y.start(), y.end(),
                y.accession(), y.filed(), provider.reportUrl(row.getCik(), y.accession()), items(y))).toList();
        long ageDays = Duration.between(row.getDownloadedAt(), LocalDateTime.now(clock)).toDays();
        return new Detail(asset.getSymbol(), asset.getName(), row.getCik(), row.getCompanyName(), row.getSource(),
                row.getDownloadedAt(), ageDays, warning == null ? DataStatus.FRESH : DataStatus.STALE, warning,
                s.price(), s.priceDate(), years, ratioViews(s, benchmarks(snapshots)));
    }

    /** Toutes les donnees, dans l'ordre : une donnee introuvable apparait avec une valeur null. */
    private static List<ItemView> items(FiscalYear y) {
        List<ItemView> out = new ArrayList<>();
        for (FundamentalItem item : FundamentalItem.values()) {
            FundamentalsData.ReportedValue v = y.values().get(item);
            out.add(new ItemView(item, item.label(), item.unit(), v == null ? null : v.value(),
                    v == null ? null : v.concept()));
        }
        return out;
    }
}
