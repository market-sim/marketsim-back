package tn.esprit.marketsim.analysis.fundamental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.fundamental.FundamentalRatios.Level;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.DataStatus;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.Detail;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.ItemView;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.Overview;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.RatioView;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsDtos.Summary;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.market.AssetRepository;

/**
 * Service d'analyse fondamentale : cache de 30 jours, source injoignable, reponse 503, actions seulement.
 * La base, la source et les cours sont simules (Mockito) : aucun acces reseau ni MySQL.
 */
class FundamentalsServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);
    private static final FundamentalsData DATA = CompanyFactsParserTest.parse("sec-companyfacts-synthetique.json");

    private CompanyFundamentalsRepository repository;
    private AssetRepository assets;
    private MarketDataService marketData;
    private FundamentalsProvider provider;
    private FundamentalsService service;
    private final List<CompanyFundamentals> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(CompanyFundamentalsRepository.class);
        assets = mock(AssetRepository.class);
        marketData = mock(MarketDataService.class);
        provider = mock(FundamentalsProvider.class);
        when(assets.findAll()).thenReturn(List.of(
                Asset.of("AAPL", "Apple Inc.", Asset.Type.STOCK, "192.45", 0),
                Asset.of("TSLA", "Tesla Inc.", Asset.Type.STOCK, "248.30", 0),
                Asset.of("EUR/USD", "Euro / US Dollar", Asset.Type.FOREX, "1.08", 0)));
        when(repository.findAll()).thenReturn(stored);
        when(provider.source()).thenReturn("SEC_EDGAR");
        price("AAPL", 30);
        price("TSLA", 60);
        service = service(provider);
    }

    private FundamentalsService service(FundamentalsProvider source) {
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        return new FundamentalsService(repository, assets, marketData, source, CompanyFactsParserTest.JSON, clock);
    }

    /** Cours constant sur 40 seances. */
    private void price(String symbol, double close) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            dates.add(LocalDate.of(2026, 9, 1).plusDays(i));
            closes.add(close);
        }
        when(marketData.series(symbol)).thenReturn(PriceSeries.of(symbol, "TEST", dates, closes));
    }

    /** Donnees en cache, telechargees il y a {@code daysAgo} jours. */
    private CompanyFundamentals cached(String symbol, int daysAgo) {
        CompanyFundamentals row = new CompanyFundamentals();
        row.setSymbol(symbol);
        row.setCik(DATA.cik());
        row.setCompanyName(DATA.companyName());
        row.setSource("SEC_EDGAR");
        row.setDownloadedAt(NOW.minusDays(daysAgo));
        row.setDataJson(CompanyFactsParserTest.JSON.writeValueAsString(DATA));
        stored.add(row);
        when(repository.findBySymbol(symbol)).thenReturn(Optional.of(row));
        return row;
    }

    private static RatioView ratio(List<RatioView> ratios, Ratio ratio) {
        return ratios.stream().filter(r -> r.ratio() == ratio).findFirst().orElseThrow();
    }

    // ===================== Cache =====================

    @Test
    void aRecentCacheIsUsedWithoutCallingTheSource() {
        cached("AAPL", 29);
        Detail d = service.get("aapl");

        verify(provider, never()).fetch(anyString(), any());
        assertEquals(DataStatus.FRESH, d.status());
        assertNull(d.warning());
        assertEquals(29, d.ageDays());
        assertEquals("AAPL", d.symbol());
        assertEquals("SEC_EDGAR", d.source());
        assertEquals(30, d.price());
        assertEquals(LocalDate.of(2026, 10, 10), d.priceDate(), "derniere des 40 seances");
        assertEquals(3, d.years().size());
        assertEquals(20, ratio(d.ratios(), Ratio.PER).value(), 1e-9, "cours 30 / BPA 1.5");
    }

    @Test
    void aCacheOf30DaysOrMoreIsDownloadedAgainWithTheKnownCik() {
        CompanyFundamentals row = cached("AAPL", 30);
        when(provider.fetch("AAPL", "0001234567")).thenReturn(DATA);

        Detail d = service.get("AAPL");

        verify(provider).fetch("AAPL", "0001234567");
        verify(repository).save(row);
        assertEquals(NOW, row.getDownloadedAt(), "date de telechargement mise a jour");
        assertEquals(DataStatus.FRESH, d.status());
        assertEquals(0, d.ageDays());
    }

    @Test
    void anOldCacheIsKeptWithAWarningWhenTheSourceIsDown() {
        cached("AAPL", 45);
        when(provider.fetch("AAPL", "0001234567"))
                .thenThrow(new SourceUnavailableException("SEC EDGAR est injoignable (Connection refused)."));

        Detail d = service.get("AAPL");

        assertEquals(DataStatus.STALE, d.status());
        assertEquals(45, d.ageDays());
        assertTrue(d.warning().contains("03/10/2026"), d.warning());
        assertTrue(d.warning().contains("il y a 45 jours"), d.warning());
        assertTrue(d.warning().contains("injoignable"), "la raison est donnee : " + d.warning());
        assertEquals(20, ratio(d.ratios(), Ratio.PER).value(), 1e-9, "les ratios restent calcules sur le cache");
        verify(repository, never()).save(any());
    }

    @Test
    void withoutCacheAnUnavailableSourceGivesA503() {
        when(repository.findBySymbol("AAPL")).thenReturn(Optional.empty());
        when(provider.fetch("AAPL", null)).thenThrow(new SourceUnavailableException("La SEC n'a pas répondu à temps."));

        String message = assertThrows(FundamentalsUnavailableException.class, () -> service.get("AAPL")).getMessage();
        assertTrue(message.contains("AAPL") && message.contains("aucune donnée en cache"), message);
        assertTrue(message.contains("La SEC n'a pas répondu à temps."), message);
    }

    @Test
    void aMissingUserAgentExplainsWhatToConfigure() throws Exception {
        SecHttpClient http = mock(SecHttpClient.class);
        FundamentalsService unconfigured = service(new SecEdgarProvider(http, CompanyFactsParserTest.JSON, ""));
        when(repository.findBySymbol("AAPL")).thenReturn(Optional.empty());

        String message = assertThrows(FundamentalsUnavailableException.class, () -> unconfigured.get("AAPL"))
                .getMessage();
        assertTrue(message.contains("SEC_USER_AGENT"), message);
        assertTrue(unconfigured.overview().configurationProblem().contains("SEC_USER_AGENT"));
        verify(http, never()).get(anyString(), anyString());
    }

    @Test
    void theDownloadedDataIsStoredAsJsonAndReadBackIdentically() {
        when(repository.findBySymbol("AAPL")).thenReturn(Optional.empty());
        when(provider.fetch("AAPL", null)).thenReturn(DATA);

        service.get("AAPL");

        ArgumentCaptor<CompanyFundamentals> saved = ArgumentCaptor.forClass(CompanyFundamentals.class);
        verify(repository).save(saved.capture());
        CompanyFundamentals row = saved.getValue();
        assertEquals("AAPL", row.getSymbol());
        assertEquals("0001234567", row.getCik());
        assertEquals("Exemple Corp", row.getCompanyName());
        assertEquals("SEC_EDGAR", row.getSource());
        assertEquals(NOW, row.getDownloadedAt());
        assertEquals(DATA, CompanyFactsParserTest.JSON.readValue(row.getDataJson(), FundamentalsData.class));
    }

    // ===================== Actions seulement =====================

    @Test
    void onlyStocksAreAnalysed() {
        String message = assertThrows(IllegalArgumentException.class, () -> service.get("EUR/USD")).getMessage();
        assertTrue(message.contains("ne concerne que les actions") && message.contains("paire de devises"), message);
        assertThrows(NotFoundException.class, () -> service.get("ZZZZ"));
        verify(provider, never()).fetch(anyString(), any());
    }

    // ===================== Rafraichissement force =====================

    @Test
    void aForcedRefreshDownloadsEvenARecentCache() {
        cached("AAPL", 1);
        when(provider.fetch("AAPL", "0001234567")).thenReturn(DATA);
        assertEquals(DataStatus.FRESH, service.refresh("AAPL").status());
        verify(provider).fetch("AAPL", "0001234567");
    }

    @Test
    void aFailedRefreshIsA503ThatMentionsTheCache() {
        cached("AAPL", 1);
        when(provider.fetch("AAPL", "0001234567")).thenThrow(new SourceUnavailableException("HTTP 503."));
        String message = assertThrows(FundamentalsUnavailableException.class, () -> service.refresh("AAPL"))
                .getMessage();
        assertTrue(message.contains("16/11/2026") && message.contains("restent utilisées"), message);
    }

    // ===================== Vue d'ensemble et repere =====================

    @Test
    void theOverviewOnlyReadsTheCache() {
        cached("AAPL", 2);
        cached("TSLA", 40);

        Overview o = service.overview();

        verify(provider, never()).fetch(anyString(), any());
        assertEquals("SEC_EDGAR", o.source());
        assertEquals(30, o.refreshAfterDays());
        assertEquals(List.of("AAPL", "TSLA"), o.stocks().stream().map(Summary::symbol).toList(), "actions seulement");
        Summary aapl = o.stocks().get(0);
        Summary tsla = o.stocks().get(1);
        assertEquals(DataStatus.FRESH, aapl.status());
        assertEquals(DataStatus.STALE, tsla.status(), "plus de 30 jours");
        assertEquals(2025, aapl.fiscalYear());
        assertEquals(LocalDate.of(2025, 12, 31), aapl.fiscalYearEnd());
        // memes comptes, cours de 30 et de 60 : PER de 20 et de 40 -> AAPL bas, TSLA eleve (2 actions comparees)
        assertEquals(Level.LOW, ratio(aapl.ratios(), Ratio.PER).level());
        assertEquals(Level.HIGH, ratio(tsla.ratios(), Ratio.PER).level());
        assertEquals(2, ratio(tsla.ratios(), Ratio.PER).compared());
    }

    @Test
    void aStockNeverDownloadedIsListedAsMissing() {
        cached("AAPL", 2);
        Overview o = service.overview();
        Summary tsla = o.stocks().get(1);
        assertEquals(DataStatus.MISSING, tsla.status());
        assertNull(tsla.downloadedAt());
        assertTrue(tsla.ratios().isEmpty());
        RatioView per = ratio(o.stocks().get(0).ratios(), Ratio.PER);
        assertNull(per.level(), "seule action analysee : pas de repere");
        assertEquals(1, per.compared());
    }

    @Test
    void withoutPriceOnlyThePriceRatiosAreMissing() {
        cached("AAPL", 2);
        when(marketData.series("AAPL")).thenThrow(new IllegalArgumentException("Yahoo injoignable"));
        Detail d = service.get("AAPL");
        assertNull(d.price());
        assertNull(ratio(d.ratios(), Ratio.PER).value());
        assertEquals(FundamentalRatios.PRICE_MISSING, ratio(d.ratios(), Ratio.PER).unavailableReason());
        assertEquals(0.25, ratio(d.ratios(), Ratio.ROE).value(), 1e-9, "150 / 600");
    }

    @Test
    void everyDatumIsListedAndAMissingOneSaysSo() {
        cached("AAPL", 2);
        Detail d = service.get("AAPL");
        List<ItemView> items = d.years().get(0).items();
        assertEquals(FundamentalItem.values().length, items.size(), "les sept donnees, meme introuvables");
        ItemView dividend = items.stream().filter(i -> i.item() == FundamentalItem.DIVIDEND_PER_SHARE).findFirst()
                .orElseThrow();
        assertNull(dividend.value(), "non disponible, jamais 0");
        assertNull(dividend.concept());
        ItemView debt = items.stream().filter(i -> i.item() == FundamentalItem.TOTAL_DEBT).findFirst().orElseThrow();
        assertEquals(300, debt.value());
        assertNotNull(debt.concept());
        RatioView yield = ratio(d.ratios(), Ratio.DIVIDEND_YIELD);
        assertNull(yield.value());
        assertEquals(FundamentalRatios.NO_DIVIDEND, yield.unavailableReason());
        assertNotNull(yield.explanation(), "chaque ratio a son explication");
    }
}
