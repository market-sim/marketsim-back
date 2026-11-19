package tn.esprit.marketsim.analysis.backtest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.Prices;
import tn.esprit.marketsim.analysis.Strategy;
import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyRules;
import tn.esprit.marketsim.analysis.StrategyService;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestDetail;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestRequest;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestResult;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.CompareRequest;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.ComparisonResult;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.ExitReason;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service de backtest : validations, coupure entrainement / test, enregistrement et propriete.
 * La base et le telechargement des cours sont simules (Mockito) : aucun acces reseau ni MySQL.
 */
class BacktestServiceTest {

    private static final LocalDate START = LocalDate.of(2023, 1, 1);
    private static final BacktestSettings DEFAULTS = new BacktestSettings(10_000, 0.001, 0, null, null, 1);

    private BacktestRepository repository;
    private StrategyService strategies;
    private MarketDataService marketData;
    private SimulationDefaultsService defaults;
    private BacktestService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(BacktestRepository.class);
        strategies = mock(StrategyService.class);
        marketData = mock(MarketDataService.class);
        defaults = mock(SimulationDefaultsService.class);
        when(defaults.current()).thenReturn(BacktestSettings.BUILT_IN);     // parametres par defaut d'origine
        service = new BacktestService(repository, strategies, marketData, JsonMapper.builder().build(), defaults);
        user = new User();
        user.setId(7L);
    }

    // ===================== Jeux de donnees =====================

    private static PriceSeries series(int n, IntToDoubleFunction price) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dates.add(START.plusDays(i));
            prices.add(price.applyAsDouble(i));
        }
        return PriceSeries.of("TEST", "TEST", dates, prices);
    }

    /** Tendance haussiere avec des oscillations : de nombreux croisements de moyennes. */
    private static PriceSeries wavy(int n) {
        return series(n, i -> 100 + 0.2 * i + 5 * Math.sin(i / 4.0));
    }

    private static StrategyParams params(StrategyType type) {
        return StrategyRules.normalize(type, null);
    }

    private static StrategyParams smaCross(int fast, int slow) {
        StrategyParams p = new StrategyParams();
        p.setFastPeriod(fast);
        p.setSlowPeriod(slow);
        return p;
    }

    private static BacktestResult simulate(PriceSeries series, StrategyType type, StrategyParams params,
                                           LocalDate from, LocalDate to, LocalDate split) {
        return BacktestService.simulate(series, type, params, DEFAULTS, from, to, split);
    }

    private static BacktestRequest request(Double capital, Double fee, Double slippage, Double stopLoss,
                                           Double takeProfit, Double fraction) {
        return new BacktestRequest(5L, "TEST", null, null, capital, fee, slippage, stopLoss, takeProfit, fraction, null);
    }

    private static CompareRequest compareRequest(List<Long> ids, LocalDate split) {
        return new CompareRequest(ids, "TEST", null, null, 5000.0, 0.002, 0.001, 0.05, 0.10, 0.8, split);
    }

    private static String messageOf(Runnable call) {
        return assertThrows(IllegalArgumentException.class, call::run).getMessage();
    }

    // ===================== Reglages =====================

    @Test
    void settingsUseTheDefaultValues() {
        BacktestSettings s = BacktestService.settings(request(null, null, null, null, null, null));
        assertEquals(10_000, s.initialCapital());
        assertEquals(0.001, s.feeRate(), "0,1 % par ordre, comme le module 6");
        assertEquals(0, s.slippage());
        assertNull(s.stopLoss());
        assertNull(s.takeProfit());
        assertEquals(1, s.positionFraction());
    }

    @Test
    void settingsKeepTheGivenValues() {
        BacktestSettings s = BacktestService.settings(request(5000.0, 0.002, 0.0005, 0.05, 0.10, 0.5));
        assertEquals(new BacktestSettings(5000, 0.002, 0.0005, 0.05, 0.10, 0.5), s);
    }

    @Test
    void settingsRejectIncoherentValues() {
        assertTrue(messageOf(() -> BacktestService.settings(request(0.0, null, null, null, null, null)))
                .startsWith("initialCapital"));
        assertTrue(messageOf(() -> BacktestService.settings(request(-100.0, null, null, null, null, null)))
                .startsWith("initialCapital"));
        assertTrue(messageOf(() -> BacktestService.settings(request(null, -0.001, null, null, null, null)))
                .startsWith("feeRate"));
        assertTrue(messageOf(() -> BacktestService.settings(request(null, 0.1, null, null, null, null)))
                .startsWith("feeRate"), "0.1 voudrait dire 10 % : erreur d'unite probable");
        assertTrue(messageOf(() -> BacktestService.settings(request(null, null, 0.5, null, null, null)))
                .startsWith("slippage"));
        // 0 veut dire « aucun stop-loss » (pour desactiver un stop-loss des parametres par defaut) ; negatif : refuse
        assertNull(BacktestService.settings(request(null, null, null, 0.0, null, null)).stopLoss());
        assertTrue(messageOf(() -> BacktestService.settings(request(null, null, null, -0.05, null, null)))
                .startsWith("stopLoss"));
        assertTrue(messageOf(() -> BacktestService.settings(request(null, null, null, 5.0, null, null)))
                .startsWith("stopLoss"), "5 voudrait dire 500 % : il faut 0.05");
        assertTrue(messageOf(() -> BacktestService.settings(request(null, null, null, null, -0.1, null)))
                .startsWith("takeProfit"));
        assertTrue(messageOf(() -> BacktestService.settings(request(null, null, null, null, null, 0.0)))
                .startsWith("positionFraction"));
        assertTrue(messageOf(() -> BacktestService.settings(request(null, null, null, null, null, 1.5)))
                .startsWith("positionFraction"));
        assertTrue(messageOf(() -> BacktestService.settings(request(Double.NaN, null, null, null, null, null)))
                .startsWith("initialCapital"));
    }

    // ===================== Simulation =====================

    @Test
    void buyAndHoldOnARisingSeries() {
        // prix = 100 + i : achat le jour 1 a 101, vente le dernier jour (99) a 199, frais 0,1 % a chaque ordre
        BacktestResult r = simulate(series(100, i -> 100 + i), StrategyType.BUY_AND_HOLD, new StrategyParams(),
                null, null, null);
        double expected = 10_000 * 0.999 * (199.0 / 101) * 0.999;

        assertEquals(expected, r.metrics().finalCapital(), 0.01);
        assertEquals(1, r.metrics().tradeCount());
        assertEquals(ExitReason.END_OF_PERIOD, r.trades().get(0).reason());
        assertEquals(1.0, r.metrics().winRate());
        assertNull(r.metrics().profitFactor(), "aucun trade perdant");
        assertEquals(0.99, r.metrics().buyHoldReturn(), 1e-6, "reference : 100 -> 199 sans frais");
        assertTrue(r.metrics().excessReturn() < 0, "la strategie paie des frais et entre un jour plus tard");
        assertEquals(98.0 / 99, r.metrics().exposure(), 1e-6);
    }

    @Test
    void curvesHaveOnePointPerSessionAndStartAtTheInitialCapital() {
        BacktestResult r = simulate(wavy(300), StrategyType.SMA_CROSS, smaCross(5, 20), null, null, null);
        assertEquals(300, r.curves().dates().length);
        assertEquals(300, r.curves().equity().length);
        assertEquals(300, r.curves().buyHold().length);
        assertEquals(300, r.curves().drawdown().length);
        assertEquals(10_000, r.curves().equity()[0]);
        assertEquals(10_000, r.curves().buyHold()[0]);
        for (double dd : r.curves().drawdown()) assertTrue(dd <= 0);
        assertTrue(r.metrics().tradeCount() > 0);
        assertEquals(r.metrics().tradeCount(), r.trades().size());
        assertNull(r.train(), "pas de date de coupure");
        assertNull(r.test());
    }

    @Test
    void theRequestedPeriodIsRespected() {
        LocalDate from = START.plusDays(100);
        LocalDate to = START.plusDays(249);
        BacktestResult r = simulate(wavy(300), StrategyType.RSI, params(StrategyType.RSI), from, to, null);
        assertEquals(150, r.curves().dates().length);
        assertEquals(from, r.curves().dates()[0]);
        assertEquals(to, r.curves().dates()[149]);
        for (var t : r.trades()) {
            assertTrue(t.entryDate().isAfter(from), "aucune execution le premier jour");
            assertTrue(!t.exitDate().isAfter(to));
        }
    }

    @Test
    void aFlatMarketGivesNoTradeAndNeutralMetrics() {
        BacktestResult r = simulate(series(200, i -> 100), StrategyType.SMA_CROSS, smaCross(5, 20), null, null, null);
        assertEquals(0, r.metrics().tradeCount());
        assertTrue(r.trades().isEmpty());
        assertEquals(0, r.metrics().totalReturn());
        assertEquals(10_000, r.metrics().finalCapital());
        assertEquals(0, r.metrics().sharpe());
        assertEquals(0, r.metrics().volatility());
        assertEquals(0, r.metrics().maxDrawdown());
        assertEquals(0, r.metrics().exposure());
        assertNull(r.metrics().winRate());
        assertNull(r.metrics().profitFactor());
        assertNull(r.metrics().averageWin());
        assertNull(r.metrics().averageHoldingDays());
        assertNull(r.metrics().sortino());
        assertNull(r.metrics().calmar());
    }

    // ===================== Periodes invalides =====================

    @Test
    void rejectsAPeriodThatIsTooShort() {
        String message = messageOf(() -> simulate(wavy(300), StrategyType.RSI, params(StrategyType.RSI),
                START, START.plusDays(19), null));
        assertTrue(message.contains("trop courte"), message);
        assertTrue(message.contains("20"), message);
    }

    @Test
    void rejectsAnInsufficientHistory() {
        // 60 seances : le croisement SMA(20,50) a besoin de 51 seances, il n'en reste que 10 exploitables
        String message = messageOf(() -> simulate(wavy(60), StrategyType.SMA_CROSS,
                params(StrategyType.SMA_CROSS), null, null, null));
        assertTrue(message.contains("historique insuffisant"), message);
        assertTrue(message.contains("51"), message);
    }

    // ===================== Comparaison =====================

    @Test
    void comparisonReusesOneSeriesAndReturnsCurvesBenchmarkAndSplitMetrics() {
        Strategy sma = strategy(5L, "SMA", StrategyType.SMA_CROSS);
        sma.setParams(smaCross(5, 20));
        Strategy rsi = strategy(6L, "RSI", StrategyType.RSI);
        LocalDate split = START.plusDays(150);
        PriceSeries prices = wavy(300);
        when(marketData.series("TEST")).thenReturn(prices);

        ComparisonResult result = service.compare(compareRequest(List.of(5L, 6L), split), user);

        assertEquals("TEST", result.symbol());
        assertEquals(START, result.from());
        assertEquals(START.plusDays(299), result.to());
        assertEquals(split, result.splitDate());
        assertEquals(prices.hash(), result.dataHash());
        assertEquals(new BacktestSettings(5000, 0.002, 0.001, 0.05, 0.10, 0.8), result.settings());
        assertEquals(300, result.dates().length);
        assertEquals(300, result.buyHold().length);
        assertEquals(2, result.strategies().size());
        assertEquals(sma.getId(), result.strategies().get(0).strategyId());
        assertEquals(rsi.getId(), result.strategies().get(1).strategyId());

        // chaque strategie donne exactement le resultat d'un backtest seul avec les memes reglages :
        // la comparaison reutilise le moteur, elle ne refait aucun calcul de son cote. Comme un backtest seul,
        // elle travaille sur les clotures arrondies a 6 decimales (Prices.normalized).
        BacktestSettings settings = new BacktestSettings(5000, 0.002, 0.001, 0.05, 0.10, 0.8);
        PriceSeries normalized = Prices.normalized(prices);
        BacktestResult smaAlone = BacktestService.simulate(normalized, StrategyType.SMA_CROSS, smaCross(5, 20), settings,
                null, null, split);
        BacktestResult rsiAlone = BacktestService.simulate(normalized, StrategyType.RSI, params(StrategyType.RSI), settings,
                null, null, split);
        assertTrue(smaAlone.metrics().tradeCount() > 0, "le jeu de donnees doit produire des trades");
        List<BacktestResult> alone = List.of(smaAlone, rsiAlone);
        for (int i = 0; i < 2; i++) {
            var compared = result.strategies().get(i);
            BacktestResult expected = alone.get(i);
            assertEquals(expected.metrics(), compared.metrics());
            assertNotNull(compared.train());
            assertNotNull(compared.test());
            assertEquals(expected.train(), compared.train());
            assertEquals(expected.test(), compared.test());
            assertArrayEquals(expected.curves().equity(), compared.curves().equity());
            assertArrayEquals(expected.curves().drawdown(), compared.curves().drawdown());
        }
        // dates et buy & hold communs, envoyes une seule fois
        assertArrayEquals(smaAlone.curves().dates(), result.dates());
        assertArrayEquals(smaAlone.curves().buyHold(), result.buyHold());
        assertArrayEquals(rsiAlone.curves().buyHold(), result.buyHold());

        verify(marketData).series("TEST");     // un seul chargement des cours pour les deux strategies
        verify(repository, never()).save(any(Backtest.class));
        verify(strategies, never()).recordBacktest(any(), any(), any(), any(), any());
    }

    @Test
    void comparisonRejectsAStrategyWithoutTypeAndNamesIt() {
        strategy(5L, "RSI", StrategyType.RSI);
        strategy(6L, "Ancienne stratégie", null);

        String message = messageOf(() -> service.compare(compareRequest(List.of(5L, 6L), null), user));

        assertTrue(message.contains("Ancienne stratégie"), message);
        assertTrue(message.contains("type"), message);
        verify(marketData, never()).series(anyString());
    }

    @Test
    void anInsufficientHistoryNamesTheStrategyInCause() {
        // 60 seances : RSI(14) a assez d'historique, le croisement SMA(20,50) non (il lui faut 51 seances)
        strategy(5L, "RSI rapide", StrategyType.RSI);
        strategy(6L, "SMA lente", StrategyType.SMA_CROSS);
        when(marketData.series("TEST")).thenReturn(wavy(60));

        String message = messageOf(() -> service.compare(compareRequest(List.of(5L, 6L), null), user));

        assertTrue(message.startsWith("« SMA lente » — "), message);
        assertTrue(message.contains("historique insuffisant"), message);
        assertTrue(message.contains("51"), message);
    }

    @Test
    void anErrorCommonToAllStrategiesIsNotAttributedToOne() {
        strategy(5L, "RSI rapide", StrategyType.RSI);
        strategy(6L, "SMA lente", StrategyType.SMA_CROSS);
        when(marketData.series("TEST")).thenReturn(wavy(300));
        // 20 seances : trop court quelle que soit la strategie
        CompareRequest req = new CompareRequest(List.of(5L, 6L), "TEST", START, START.plusDays(19),
                null, null, null, null, null, null, null);

        String message = messageOf(() -> service.compare(req, user));

        assertTrue(message.startsWith("Période trop courte"), message);
        assertTrue(!message.contains("RSI rapide") && !message.contains("SMA lente"), message);
    }

    @Test
    void comparisonRejectsInvalidCountsAndDuplicateIdsBeforeLoadingPrices() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.compare(null, user)).getMessage().contains("obligatoire"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.compare(compareRequest(List.of(5L), null), user)).getMessage().contains("2 et 5"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.compare(compareRequest(List.of(5L, 6L, 7L, 8L, 9L, 10L), null), user))
                .getMessage().contains("2 et 5"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.compare(compareRequest(List.of(5L, 5L), null), user)).getMessage().contains("distinctes"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.compare(compareRequest(List.of(0L, 6L), null), user)).getMessage().contains("positif"));
        verify(marketData, never()).series(anyString());
    }

    @Test
    void comparisonOfAnotherUsersStrategyReturnsNotFoundBeforeLoadingPrices() {
        strategy(6L, "RSI", StrategyType.RSI);
        when(strategies.findOne(5L, user)).thenThrow(new NotFoundException("Strategie introuvable"));

        assertTrue(assertThrows(NotFoundException.class,
                () -> service.compare(compareRequest(List.of(5L, 6L), null), user))
                .getMessage().contains("introuvable"));

        verify(marketData, never()).series(anyString());
    }

    @Test
    void comparisonRejectsInvalidStrategyBeforeLoadingPrices() {
        Strategy invalid = strategy(5L, "SMA invalide", StrategyType.SMA_CROSS);
        invalid.setParams(smaCross(20, 5));
        strategy(6L, "RSI", StrategyType.RSI);

        assertThrows(IllegalArgumentException.class,
                () -> service.compare(compareRequest(List.of(5L, 6L), null), user));

        verify(marketData, never()).series(anyString());
        verify(repository, never()).save(any(Backtest.class));
    }

    @Test
    void historyBeforeThePeriodServesAsWarmup() {
        // meme strategie, mais la periode commence apres 100 seances d'historique : pas de chauffe a attendre
        BacktestResult r = simulate(wavy(300), StrategyType.SMA_CROSS, params(StrategyType.SMA_CROSS),
                START.plusDays(100), START.plusDays(139), null);
        assertEquals(40, r.curves().dates().length);
    }

    @Test
    void rejectsIncoherentDates() {
        PriceSeries s = wavy(300);
        StrategyParams p = params(StrategyType.RSI);
        assertTrue(messageOf(() -> simulate(s, StrategyType.RSI, p, START.plusDays(50), START.plusDays(10), null))
                .contains("date de début"));
        assertTrue(messageOf(() -> simulate(s, StrategyType.RSI, p, START.plusDays(50), START.plusDays(50), null))
                .contains("date de début"));
        assertTrue(messageOf(() -> simulate(s, StrategyType.RSI, p, START.plusYears(5), null, null))
                .contains("Aucune donnée"));
    }

    // ===================== Entrainement / test =====================

    @Test
    void splitDateGivesSeparateMetricsBeforeAndAfter() {
        PriceSeries s = wavy(300);
        LocalDate split = START.plusDays(150);
        BacktestResult r = simulate(s, StrategyType.SMA_CROSS, smaCross(5, 20), null, null, split);

        assertEquals(START, r.train().from());
        assertEquals(split.minusDays(1), r.train().to(), "l'entrainement s'arrete la veille de la coupure");
        assertEquals(split, r.test().from(), "le test commence a la date de coupure");
        assertEquals(START.plusDays(299), r.test().to());

        // chaque sous-periode est simulee a part, depuis le capital initial et sans position
        BacktestResult trainAlone = simulate(s, StrategyType.SMA_CROSS, smaCross(5, 20), null, split.minusDays(1), null);
        BacktestResult testAlone = simulate(s, StrategyType.SMA_CROSS, smaCross(5, 20), split, null, null);
        assertEquals(trainAlone.metrics(), r.train().metrics());
        assertEquals(testAlone.metrics(), r.test().metrics());

        // la periode entiere n'est pas modifiee par la coupure
        assertEquals(simulate(s, StrategyType.SMA_CROSS, smaCross(5, 20), null, null, null).metrics(), r.metrics());
    }

    @Test
    void rejectsASplitDateOutsideThePeriodOrTooCloseToItsEnds() {
        PriceSeries s = wavy(300);
        StrategyParams p = smaCross(5, 20);
        assertTrue(messageOf(() -> simulate(s, StrategyType.SMA_CROSS, p, null, null, START.minusDays(1)))
                .contains("date de coupure"));
        assertTrue(messageOf(() -> simulate(s, StrategyType.SMA_CROSS, p, null, null, START))
                .contains("date de coupure"), "la coupure doit laisser une periode d'entrainement");
        assertTrue(messageOf(() -> simulate(s, StrategyType.SMA_CROSS, p, null, null, START.plusDays(400)))
                .contains("date de coupure"));
        assertTrue(messageOf(() -> simulate(s, StrategyType.SMA_CROSS, p, null, null, START.plusDays(290)))
                .startsWith("Période de test trop courte"));
        assertTrue(messageOf(() -> simulate(s, StrategyType.SMA_CROSS, p, null, null, START.plusDays(10)))
                .startsWith("Période d'entraînement trop courte"));
    }

    // ===================== Enregistrement et propriete =====================

    private Strategy strategy(StrategyType type) {
        return strategy(5L, "Ma strategie", type);
    }

    private Strategy strategy(Long id, String name, StrategyType type) {
        Strategy s = new Strategy();
        s.setId(id);
        s.setName(name);
        s.setType(type);
        s.setOwner(user);
        when(strategies.findOne(id, user)).thenReturn(s);
        return s;
    }

    private Backtest savedBacktest() {
        ArgumentCaptor<Backtest> captor = ArgumentCaptor.forClass(Backtest.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void runSavesTheBacktestAndUpdatesTheStrategySummary() {
        Strategy strategy = strategy(StrategyType.BUY_AND_HOLD);
        PriceSeries series = series(100, i -> 100 + i);
        when(marketData.series("TEST")).thenReturn(series);

        BacktestDetail detail = service.run(request(null, null, null, null, null, null), user);

        Backtest saved = savedBacktest();
        assertEquals(user, saved.getOwner());
        assertEquals(strategy, saved.getStrategy());
        assertEquals("Ma strategie", saved.getStrategyName());
        assertEquals(StrategyType.BUY_AND_HOLD, saved.getStrategyType());
        assertEquals("TEST", saved.getSymbol());
        assertEquals(START, saved.getFromDate());
        assertEquals(START.plusDays(99), saved.getToDate());
        assertEquals(series.hash(), saved.getDataHash(), "empreinte des donnees utilisees");
        assertEquals(1, saved.getTrades());
        assertEquals(detail.metrics().totalReturn(), saved.getTotalReturn());
        assertNotNull(saved.getResultJson());
        assertNotNull(saved.getSettingsJson());

        // resume recopie dans la strategie : winRate et maxDrawdown en pourcentage
        ArgumentCaptor<Double> sharpe = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> winRate = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> maxDrawdown = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Integer> trades = ArgumentCaptor.forClass(Integer.class);
        verify(strategies).recordBacktest(any(Strategy.class), sharpe.capture(), winRate.capture(),
                maxDrawdown.capture(), trades.capture());
        assertEquals(100.0, winRate.getValue(), "1 trade gagnant sur 1 = 100 %");
        assertEquals(-0.1, maxDrawdown.getValue(), "seule baisse : les frais d'achat de 0,1 %");
        assertEquals(1, trades.getValue());
        assertEquals(Rounding.round(detail.metrics().sharpe(), 2), sharpe.getValue());
    }

    @Test
    void aSavedBacktestIsReadBackIdentically() {
        Strategy strategy = strategy(StrategyType.SMA_CROSS);
        strategy.setParams(smaCross(5, 20));
        when(marketData.series("TEST")).thenReturn(wavy(300));
        BacktestRequest req = new BacktestRequest(5L, "TEST", null, null, 5000.0, 0.002, 0.001, 0.05, 0.10, 0.8,
                START.plusDays(150));

        BacktestDetail created = service.run(req, user);

        Backtest saved = savedBacktest();
        saved.setId(42L);
        saved.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        when(repository.findByIdAndOwnerId(42L, 7L)).thenReturn(Optional.of(saved));
        BacktestDetail read = service.findOne(42L, user);

        // le JSON enregistre redonne exactement les memes objets
        assertEquals(created.metrics(), read.metrics());
        assertEquals(created.train(), read.train());
        assertEquals(created.test(), read.test());
        assertEquals(created.trades(), read.trades());
        assertEquals(created.settings(), read.settings());
        assertArrayEquals(created.curves().dates(), read.curves().dates());
        assertArrayEquals(created.curves().equity(), read.curves().equity());
        assertArrayEquals(created.curves().buyHold(), read.curves().buyHold());
        assertArrayEquals(created.curves().drawdown(), read.curves().drawdown());
        assertEquals(5, read.params().getFastPeriod());
        assertEquals(20, read.params().getSlowPeriod());
        assertEquals(new BacktestSettings(5000, 0.002, 0.001, 0.05, 0.10, 0.8), read.settings());
        assertEquals(START.plusDays(150), read.splitDate());
        assertEquals(42L, read.id());
        assertEquals(5L, read.strategyId());
    }

    @Test
    void aStrategyWithoutTypeCannotBeBacktested() {
        strategy(null);
        String message = messageOf(() -> service.run(request(null, null, null, null, null, null), user));
        assertTrue(message.contains("type"), message);
        verify(marketData, never()).series(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void invalidSettingsAreRejectedBeforeDownloadingPrices() {
        strategy(StrategyType.RSI);
        assertThrows(IllegalArgumentException.class,
                () -> service.run(request(-1.0, null, null, null, null, null), user));
        verify(marketData, never()).series(anyString());
    }

    @Test
    void strategyAndSymbolAreMandatory() {
        assertTrue(messageOf(() -> service.run(new BacktestRequest(null, "TEST", null, null, null, null, null,
                null, null, null, null), user)).startsWith("strategyId"));
        assertTrue(messageOf(() -> service.run(new BacktestRequest(5L, " ", null, null, null, null, null,
                null, null, null, null), user)).startsWith("symbol"));
    }

    @Test
    void theStrategyOfAnotherUserIsNotFound() {
        when(strategies.findOne(5L, user)).thenThrow(new NotFoundException("Strategie introuvable"));
        assertThrows(NotFoundException.class, () -> service.run(request(null, null, null, null, null, null), user));
        verify(repository, never()).save(any());
    }

    @Test
    void anUnknownAssetIsNotFound() {
        strategy(StrategyType.RSI);
        when(marketData.series("TEST")).thenThrow(new NotFoundException("Actif inconnu : TEST"));
        assertThrows(NotFoundException.class, () -> service.run(request(null, null, null, null, null, null), user));
        verify(repository, never()).save(any());
    }

    @Test
    void theBacktestOfAnotherUserIsNotFound() {
        // la recherche se fait toujours avec l'identifiant du proprietaire : rien n'est trouve pour un autre
        when(repository.findByIdAndOwnerId(42L, 7L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.findOne(42L, user));
        assertThrows(NotFoundException.class, () -> service.delete(42L, user));
        verify(repository, never()).delete(any(Backtest.class));
    }

    @Test
    void listIsLimitedToMyBacktestsAndCanBeFilteredByStrategy() {
        BacktestSummary a = summary(1L, 5L);
        BacktestSummary b = summary(2L, 6L);
        when(repository.summaries(7L)).thenReturn(List.of(a, b));
        assertEquals(List.of(a, b), service.findMine(user, null));
        assertEquals(List.of(b), service.findMine(user, 6L));
        assertTrue(service.findMine(user, 99L).isEmpty());
    }

    private static BacktestSummary summary(Long id, Long strategyId) {
        return new BacktestSummary(id, strategyId, "S", StrategyType.RSI, "TEST", START, START.plusDays(99), null,
                LocalDateTime.of(2026, 1, 1, 12, 0), 0.1, 0.2, 1.0, -0.05, 3, 0.5, 0.08, 0.02);
    }

    @Test
    void deletingABacktestPutsThePreviousOneBackInTheStrategySummary() {
        Strategy strategy = strategy(StrategyType.RSI);
        Backtest deleted = backtest(42L, strategy, 2.0, 0.6, -0.10, 8);
        Backtest previous = backtest(41L, strategy, 1.234, 0.5, -0.2046, 4);
        when(repository.findByIdAndOwnerId(42L, 7L)).thenReturn(Optional.of(deleted));
        when(repository.findFirstByStrategyIdOrderByCreatedAtDescIdDesc(5L)).thenReturn(Optional.of(previous));

        service.delete(42L, user);

        verify(repository).delete(deleted);
        verify(strategies).recordBacktest(strategy, 1.23, 50.0, -20.46, 4);
    }

    @Test
    void deletingTheLastBacktestEmptiesTheStrategySummary() {
        Strategy strategy = strategy(StrategyType.RSI);
        Backtest deleted = backtest(42L, strategy, 2.0, 0.6, -0.10, 8);
        when(repository.findByIdAndOwnerId(42L, 7L)).thenReturn(Optional.of(deleted));
        when(repository.findFirstByStrategyIdOrderByCreatedAtDescIdDesc(5L)).thenReturn(Optional.empty());

        service.delete(42L, user);

        verify(repository).delete(deleted);
        verify(strategies).recordBacktest(strategy, null, null, null, null);
    }

    // ===================== Fiabilite : prix normalises, annualisation, taux sans risque =====================

    @Test
    void unroundedAndRoundedClosesGiveTheSameResult() {
        // juste apres un telechargement, le module 6 renvoie des clotures non arrondies ; ensuite, celles du cache
        PriceSeries raw = wavy(300);
        PriceSeries cached = PriceSeries.decode(raw.symbol(), raw.source(), raw.encode());
        Strategy sma = strategy(5L, "SMA", StrategyType.SMA_CROSS);
        sma.setParams(smaCross(5, 20));
        strategy(6L, "RSI", StrategyType.RSI);
        CompareRequest req = compareRequest(List.of(5L, 6L), START.plusDays(150));

        when(marketData.series("TEST")).thenReturn(raw);
        ComparisonResult fromDownload = service.compare(req, user);
        BacktestDetail backtestFromDownload = service.run(new BacktestRequest(5L, "TEST", null, null, null, null, null,
                null, null, null, null), user);
        when(marketData.series("TEST")).thenReturn(cached);
        ComparisonResult fromCache = service.compare(req, user);
        BacktestDetail backtestFromCache = service.run(new BacktestRequest(5L, "TEST", null, null, null, null, null,
                null, null, null, null), user);

        for (int i = 0; i < 2; i++) {
            assertEquals(fromCache.strategies().get(i).metrics(), fromDownload.strategies().get(i).metrics());
            assertEquals(fromCache.strategies().get(i).test(), fromDownload.strategies().get(i).test());
            assertArrayEquals(fromCache.strategies().get(i).curves().equity(), fromDownload.strategies().get(i).curves().equity());
        }
        assertEquals(fromCache.periodHash(), fromDownload.periodHash());
        assertEquals(backtestFromCache.metrics(), backtestFromDownload.metrics());
        assertEquals(backtestFromCache.trades(), backtestFromDownload.trades());
    }

    @Test
    void theRiskFreeRateIsAnOptionalSetting() {
        assertEquals(0, BacktestService.settings(request(null, null, null, null, null, null)).riskFreeRate(), 0.0);
        BacktestRequest withRate = new BacktestRequest(5L, "TEST", null, null, null, null, null, null, null, null, null, 0.03);
        assertEquals(0.03, BacktestService.settings(withRate).riskFreeRate(), 0.0);
        assertEquals(0.20, BacktestService.settings(new BacktestRequest(5L, "TEST", null, null, null, null, null, null,
                null, null, null, 0.20)).riskFreeRate(), 0.0, "20 % accepte");
        for (double bad : new double[] {-0.01, 0.21, Double.NaN}) {
            String message = messageOf(() -> BacktestService.settings(new BacktestRequest(5L, "TEST", null, null, null,
                    null, null, null, null, null, null, bad)));
            assertTrue(message.startsWith("riskFreeRate"), message);
        }
    }

    @Test
    void theAnnualisationFollowsTheAssetCalendarAndTheRiskFreeRateLowersTheSharpe() {
        PriceSeries daily = wavy(800);       // cote tous les jours, comme une cryptomonnaie
        BacktestResult noRate = BacktestService.simulate(daily, StrategyType.BUY_AND_HOLD, params(StrategyType.BUY_AND_HOLD),
                DEFAULTS, null, null, null);
        BacktestSettings withRate = new BacktestSettings(10_000, 0.001, 0, null, null, 1, 0.04);
        BacktestResult rate = BacktestService.simulate(daily, StrategyType.BUY_AND_HOLD, params(StrategyType.BUY_AND_HOLD),
                withRate, null, null, null);

        assertEquals(365, noRate.periodsPerYear());
        // recalculee sur la courbe de capital (arrondie au centime) : racine(365), pas racine(252), soit 20 % d'ecart
        double[] equity = noRate.curves().equity();
        assertEquals(PerformanceMetrics.volatility(equity, 365), noRate.metrics().volatility(), 1e-4);
        assertTrue(Math.abs(PerformanceMetrics.volatility(equity, 252) - noRate.metrics().volatility()) > 0.01);
        assertTrue(rate.metrics().sharpe() < noRate.metrics().sharpe());
        assertEquals(noRate.metrics().totalReturn(), rate.metrics().totalReturn(), "seuls Sharpe et Sortino changent");
    }

    @Test
    void comparisonCarriesTheRiskFreeRateAndThePeriodsPerYear() {
        Strategy sma = strategy(5L, "SMA", StrategyType.SMA_CROSS);
        sma.setParams(smaCross(5, 20));
        strategy(6L, "RSI", StrategyType.RSI);
        when(marketData.series("TEST")).thenReturn(wavy(300));
        CompareRequest req = new CompareRequest(List.of(5L, 6L), "TEST", null, null, null, null, null, null, null, null,
                null, 0.02);

        ComparisonResult r = service.compare(req, user);

        assertEquals(0.02, r.settings().riskFreeRate(), 0.0);
        assertEquals(365, r.periodsPerYear());
        assertEquals(Prices.periodHash(Prices.normalized(wavy(300)), r.from(), r.to()), r.periodHash());
    }

    @Test
    void aBacktestSavedBeforeTheseSettingsIsStillRead() {
        // meme reglage que le JsonMapper de Spring : un champ primitif absent est une erreur
        JsonMapper strict = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        BacktestService strictService = new BacktestService(repository, strategies, marketData, strict, defaults);
        Strategy strategy = strategy(StrategyType.BUY_AND_HOLD);
        BacktestResult result = BacktestService.simulate(wavy(200), StrategyType.BUY_AND_HOLD,
                params(StrategyType.BUY_AND_HOLD), DEFAULTS, null, null, null);

        // JSON tel qu'enregistre avant le 2026-11-19 : sans taux sans risque ni seances par an
        tools.jackson.databind.node.ObjectNode settings = strict.valueToTree(
                new BacktestDtos.StoredSettings(params(StrategyType.BUY_AND_HOLD), DEFAULTS));
        ((tools.jackson.databind.node.ObjectNode) settings.get("settings")).remove("riskFreeRate");
        tools.jackson.databind.node.ObjectNode stored = strict.valueToTree(result);
        stored.remove("periodsPerYear");
        Backtest old = backtest(42L, strategy, null, null, null, 0);
        old.setStrategyName("Ancien");
        old.setStrategyType(StrategyType.BUY_AND_HOLD);
        old.setSymbol("TEST");
        old.setSettingsJson(settings.toString());
        old.setResultJson(stored.toString());
        when(repository.findByIdAndOwnerId(42L, user.getId())).thenReturn(Optional.of(old));

        BacktestDetail detail = strictService.findOne(42L, user);

        assertEquals(0, detail.settings().riskFreeRate(), 0.0, "relu avec 0 %, la valeur utilisee a l'epoque");
        assertNull(detail.periodsPerYear(), "absent : l'ecran indique 252, la valeur fixe de l'epoque");
        assertEquals(result.metrics(), detail.metrics());
    }

    // ===================== Parametres par defaut (administration) =====================

    @Test
    void missingValuesTakeTheAdminDefaults() {
        BacktestSettings adminDefaults = new BacktestSettings(5000, 0.002, 0.0005, 0.05, 0.20, 0.5, 0.02);
        when(defaults.current()).thenReturn(adminDefaults);
        strategy(StrategyType.BUY_AND_HOLD);
        when(marketData.series("TEST")).thenReturn(wavy(200));

        BacktestDetail detail = service.run(request(null, null, null, null, null, null), user);

        assertEquals(adminDefaults, detail.settings());
    }

    @Test
    void givenValuesWinAndZeroDisablesADefaultStopLossOrTakeProfit() {
        BacktestSettings adminDefaults = new BacktestSettings(5000, 0.002, 0.0005, 0.05, 0.20, 0.5, 0.02);
        BacktestSettings s = BacktestService.settings(new BacktestRequest(5L, "TEST", null, null, 8000.0, 0.001, 0.0,
                0.0, 0.0, 1.0, null, 0.0), adminDefaults);
        assertEquals(new BacktestSettings(8000, 0.001, 0, null, null, 1, 0.0), s, "0 = aucun stop-loss ni take-profit");
        // seule la valeur absente prend le defaut
        BacktestSettings partial = BacktestService.settings(new BacktestRequest(5L, "TEST", null, null, 8000.0, null, null,
                null, null, null, null, null), adminDefaults);
        assertEquals(new BacktestSettings(8000, 0.002, 0.0005, 0.05, 0.20, 0.5, 0.02), partial);
    }

    @Test
    void theComparisonAlsoUsesTheAdminDefaults() {
        BacktestSettings adminDefaults = new BacktestSettings(7000, 0.003, 0, null, null, 1, 0.01);
        when(defaults.current()).thenReturn(adminDefaults);
        Strategy sma = strategy(5L, "SMA", StrategyType.SMA_CROSS);
        sma.setParams(smaCross(5, 20));
        strategy(6L, "RSI", StrategyType.RSI);
        when(marketData.series("TEST")).thenReturn(wavy(300));

        ComparisonResult r = service.compare(new CompareRequest(List.of(5L, 6L), "TEST", null, null, null, null, null,
                null, null, null, null), user);

        assertEquals(adminDefaults, r.settings());
    }

    // ===================== Verification des cours d'un backtest enregistre =====================

    @Test
    void aBacktestStoresThePeriodHashAndItsCheckSaysSame() {
        strategy(StrategyType.BUY_AND_HOLD);
        when(marketData.series("TEST")).thenReturn(wavy(300));
        service.run(new BacktestRequest(5L, "TEST", START.plusDays(50), START.plusDays(200), null, null, null, null,
                null, null, null), user);
        Backtest saved = savedBacktest();
        assertEquals(Prices.periodHash(Prices.normalized(wavy(300)), START.plusDays(50), START.plusDays(200)),
                saved.getPeriodHash());

        saved.setId(40L);
        when(repository.findByIdAndOwnerId(40L, user.getId())).thenReturn(Optional.of(saved));
        // la serie s'est allongee depuis : la periode est la meme
        when(marketData.series("TEST")).thenReturn(wavy(400));
        assertEquals(DataCheck.Status.SAME, service.dataCheck(40L, user).status());
        // dividende : les cours anterieurs baissent de 2 %
        when(marketData.series("TEST")).thenReturn(series(400, i -> (100 + 0.2 * i + 5 * Math.sin(i / 4.0)) * (i < 150 ? 0.98 : 1)));
        assertEquals(DataCheck.Status.CHANGED, service.dataCheck(40L, user).status());
        // source injoignable : le detail reste lisible, la verification dit seulement « indisponible »
        when(marketData.series("TEST")).thenThrow(new IllegalArgumentException("source injoignable"));
        assertEquals(DataCheck.Status.UNAVAILABLE, service.dataCheck(40L, user).status());
    }

    @Test
    void anOldBacktestIsUnverifiableWithoutLoadingPrices() {
        Strategy strategy = strategy(StrategyType.BUY_AND_HOLD);
        Backtest old = backtest(41L, strategy, 1.0, null, -0.1, 0);
        old.setSymbol("TEST");
        old.setFromDate(START);
        old.setToDate(START.plusDays(100));
        when(repository.findByIdAndOwnerId(41L, user.getId())).thenReturn(Optional.of(old));

        assertEquals(DataCheck.Status.UNVERIFIABLE, service.dataCheck(41L, user).status());
        verify(marketData, never()).series(anyString());
        assertThrows(NotFoundException.class, () -> service.dataCheck(99L, user));
    }

    private Backtest backtest(Long id, Strategy strategy, Double sharpe, Double winRate, Double maxDrawdown,
                              Integer trades) {
        Backtest b = new Backtest();
        b.setId(id);
        b.setOwner(user);
        b.setStrategy(strategy);
        b.setSharpe(sharpe);
        b.setWinRate(winRate);
        b.setMaxDrawdown(maxDrawdown);
        b.setTrades(trades);
        return b;
    }
}
