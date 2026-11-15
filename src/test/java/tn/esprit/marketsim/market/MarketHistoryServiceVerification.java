package tn.esprit.marketsim.market;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.MarketSeries;
import tn.esprit.marketsim.agent.realdata.MarketSeriesRepository;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.common.NotFoundException;

/**
 * Suite de vérification unitaire directe pour {@link MarketHistoryService}.
 */
public class MarketHistoryServiceVerification {

    public static void main(String[] args) throws Exception {
        testSuccessfulRetrievalAndPeriodFiltering();
        testUnavailableHandlingWithoutFabrication();
        testUnknownAssetThrowsNotFound();
        System.out.println("ALL MARKET HISTORY CHECKS PASSED: 3/3");
    }

    private static void testSuccessfulRetrievalAndPeriodFiltering() throws Exception {
        Asset asset = Asset.of("AAPL", "Apple Inc.", Asset.Type.STOCK, "180.00", 1.5);
        List<LocalDate> dates = List.of(
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 11, 1),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 11, 1)
        );
        List<Double> prices = List.of(150.0, 160.0, 170.0, 175.0, 180.0);
        PriceSeries mockSeries = PriceSeries.of("AAPL", "YAHOO", dates, prices);

        MarketSeries mockStored = new MarketSeries();
        mockStored.setSymbol("AAPL");
        mockStored.setSource("YAHOO");
        mockStored.setFetchedAt(LocalDateTime.now().minusHours(2));
        mockStored.setData(mockSeries.encode());
        mockStored.setPoints(mockSeries.size());

        AssetRepository assetRepo = mockProxy(AssetRepository.class, (proxy, method, args) -> {
            if ("findAll".equals(method.getName())) return List.of(asset);
            return null;
        });

        MarketSeriesRepository seriesRepo = mockProxy(MarketSeriesRepository.class, (proxy, method, args) -> {
            if ("findBySymbol".equals(method.getName())) return Optional.of(mockStored);
            return null;
        });

        MarketDataService marketDataService = createMockMarketDataService(mockSeries, null);

        MarketHistoryService service = new MarketHistoryService(assetRepo, marketDataService, seriesRepo);

        // Test ALL period
        MarketHistoryDto allDto = service.getHistory("AAPL", "ALL");
        check(allDto.status() == MarketHistoryDto.DataStatus.CACHED, "Status should be CACHED");
        check(allDto.points() == 5, "Total points should be 5, got " + allDto.points());
        check(allDto.firstClose() == 150.0, "First close should be 150.0");
        check(allDto.lastClose() == 180.0, "Last close should be 180.0");
        check(allDto.changePct() == 20.0, "Change pct should be 20.0%, got " + allDto.changePct());

        // Test 1M period (should only include dates within 1 month of 2026-11-01)
        MarketHistoryDto oneMonthDto = service.getHistory("AAPL", "1M");
        check(oneMonthDto.points() == 1, "1M points should be 1, got " + oneMonthDto.points());
        check(oneMonthDto.firstClose() == 180.0, "1M first close should be 180.0");
    }

    private static void testUnavailableHandlingWithoutFabrication() throws Exception {
        Asset asset = Asset.of("XYZ", "Unknown Inc.", Asset.Type.STOCK, "50.00", 0.0);

        AssetRepository assetRepo = mockProxy(AssetRepository.class, (proxy, method, args) -> {
            if ("findAll".equals(method.getName())) return List.of(asset);
            return null;
        });

        MarketSeriesRepository seriesRepo = mockProxy(MarketSeriesRepository.class, (proxy, method, args) -> {
            if ("findBySymbol".equals(method.getName())) return Optional.empty();
            return null;
        });

        MarketDataService marketDataService = createMockMarketDataService(null, new RuntimeException("Fournisseur Yahoo injoignable"));

        MarketHistoryService service = new MarketHistoryService(assetRepo, marketDataService, seriesRepo);

        MarketHistoryDto dto = service.getHistory("XYZ", "ALL");
        check(dto.status() == MarketHistoryDto.DataStatus.UNAVAILABLE, "Status must be UNAVAILABLE");
        check(dto.prices().isEmpty(), "No fake prices should be fabricated");
        check(dto.message() != null && dto.message().contains("injoignable"), "Error message should mention provider failure");
    }

    private static void testUnknownAssetThrowsNotFound() throws Exception {
        AssetRepository assetRepo = mockProxy(AssetRepository.class, (proxy, method, args) -> {
            if ("findAll".equals(method.getName())) return List.of();
            return null;
        });

        MarketSeriesRepository seriesRepo = mockProxy(MarketSeriesRepository.class, (proxy, method, args) -> Optional.empty());
        MarketDataService marketDataService = createMockMarketDataService(null, null);

        MarketHistoryService service = new MarketHistoryService(assetRepo, marketDataService, seriesRepo);
        try {
            service.getHistory("NOT_EXIST", "ALL");
            throw new AssertionError("Should have thrown NotFoundException");
        } catch (NotFoundException e) {
            // Success
        }
    }

    private static void check(boolean condition, String msg) {
        if (!condition) throw new AssertionError("CHECK FAILED: " + msg);
    }

    @SuppressWarnings("unchecked")
    private static <T> T mockProxy(Class<T> intf, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(intf.getClassLoader(), new Class<?>[]{intf}, handler);
    }

    private static MarketDataService createMockMarketDataService(PriceSeries series, RuntimeException error) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        DummyMarketDataService stub = (DummyMarketDataService) unsafe.allocateInstance(DummyMarketDataService.class);
        stub.seriesToReturn = series;
        stub.errorToThrow = error;
        return stub;
    }

    public static class DummyMarketDataService extends MarketDataService {
        PriceSeries seriesToReturn;
        RuntimeException errorToThrow;

        public DummyMarketDataService() {
            super(null, null);
        }

        @Override
        public PriceSeries series(String sym) {
            if (errorToThrow != null) throw errorToThrow;
            return seriesToReturn;
        }
    }
}
