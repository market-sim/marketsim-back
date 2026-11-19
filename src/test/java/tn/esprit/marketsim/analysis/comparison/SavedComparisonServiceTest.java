package tn.esprit.marketsim.analysis.comparison;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.Prices;
import tn.esprit.marketsim.analysis.Strategy;
import tn.esprit.marketsim.analysis.backtest.DataCheck;
import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyService;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.CompareRequest;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.ComparisonResult;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.StrategyComparison;
import tn.esprit.marketsim.analysis.backtest.BacktestRepository;
import tn.esprit.marketsim.analysis.backtest.BacktestSettings;
import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService;
import tn.esprit.marketsim.analysis.backtest.BacktestService;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.ComparisonDetail;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.ComparisonSummary;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.RenameRequest;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.SaveComparisonRequest;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.StrategySnapshot;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.StrategyStatus;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;
import tools.jackson.databind.json.JsonMapper;

/**
 * Historique des comparaisons : le serveur relance le calcul, le resultat relu est identique a une comparaison
 * relancee, chaque utilisateur ne voit que les siennes, et une strategie supprimee ensuite ne casse rien.
 *
 * <p>Le moteur est le vrai ({@link BacktestService}) ; seuls la base et le telechargement des cours sont simules.
 * Le depot est remplace par une petite base en memoire qui filtre par proprietaire, comme la vraie requete.
 */
class SavedComparisonServiceTest {

    private static final LocalDate START = LocalDate.of(2023, 1, 1);
    private static final LocalDate SPLIT = START.plusDays(150);

    private final JsonMapper json = JsonMapper.builder().build();
    private SavedComparisonRepository repository;
    private StrategyService strategies;
    private MarketDataService marketData;
    private BacktestService backtests;
    private SavedComparisonService service;
    private User alice;
    private User bob;
    private PriceSeries prices;

    /** Base simulee : comparaisons et strategies par identifiant. */
    private final Map<Long, SavedComparison> savedDb = new LinkedHashMap<>();
    private final Map<Long, Strategy> strategyDb = new LinkedHashMap<>();
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        repository = mock(SavedComparisonRepository.class);
        strategies = mock(StrategyService.class);
        marketData = mock(MarketDataService.class);
        SimulationDefaultsService defaults = mock(SimulationDefaultsService.class);
        when(defaults.current()).thenReturn(BacktestSettings.BUILT_IN);
        backtests = new BacktestService(mock(BacktestRepository.class), strategies, marketData, json, defaults);
        service = new SavedComparisonService(repository, backtests, strategies, json);
        alice = user(7L);
        bob = user(8L);
        prices = wavy(300);
        when(marketData.series("TEST")).thenReturn(prices);
        fakeRepository();
        fakeStrategies();

        strategy(5L, "Croisement 5/20", StrategyType.SMA_CROSS, smaCross(5, 20), alice);
        strategy(6L, "RSI standard", StrategyType.RSI, null, alice);
        strategy(9L, "Strategie de Bob", StrategyType.MACD, null, bob);
    }

    // ===================== Enregistrer =====================

    @Test
    void savingRerunsTheComparisonOnTheServerAndStoresItsResult() {
        ComparisonDetail detail = service.save(save("  Mon test  ", "ANNUALIZED_RETURN", request(5L, 6L)), alice);

        verify(marketData, times(1)).series("TEST");     // cours charges par le serveur pendant l'enregistrement
        assertEquals("Mon test", detail.name(), "espaces autour retires");
        assertEquals(ComparisonRanking.ANNUALIZED_RETURN, detail.rankBy());
        assertNotNull(detail.id());
        assertNotNull(detail.createdAt());
        assertEquals(List.of(StrategyStatus.CURRENT, StrategyStatus.CURRENT), detail.strategyStatus());

        SavedComparison stored = savedDb.get(detail.id());
        assertEquals(alice, stored.getOwner());
        assertEquals("TEST", stored.getSymbol());
        assertEquals(START, stored.getFromDate());
        assertEquals(START.plusDays(299), stored.getToDate());
        assertEquals(SPLIT, stored.getSplitDate());
        assertEquals(prices.source(), stored.getDataSource());
        assertEquals(prices.hash(), stored.getDataHash());
        assertEquals(detail.result().strategies().get(0).metrics().buyHoldReturn(), stored.getBuyHoldReturn());

        // instantane des strategies : nom, type et parametres au moment de l'enregistrement
        StrategySnapshot[] snapshots = json.readValue(stored.getStrategiesJson(), StrategySnapshot[].class);
        assertEquals(2, snapshots.length);
        assertEquals("Croisement 5/20", snapshots[0].name());
        assertEquals(StrategyType.SMA_CROSS, snapshots[0].type());
        assertEquals(5, snapshots[0].params().getFastPeriod());
        assertEquals(20, snapshots[0].params().getSlowPeriod());
        assertEquals(StrategyType.RSI, snapshots[1].type());
        assertEquals(14, snapshots[1].params().getPeriod(), "parametres completes par les regles du type");
        assertEquals(detail.result().strategies().get(0).metrics().sharpe(), snapshots[0].sharpe());
        assertEquals(detail.result().strategies().get(1).metrics().tradeCount(), snapshots[1].tradeCount());
    }

    @Test
    void theSavedResultIsTheOneComputedByTheServer() {
        // la demande ne porte que le nom, le critere et les reglages : aucun champ ne peut transporter un resultat
        assertEquals(List.of("name", "rankBy", "request"),
                java.util.Arrays.stream(SaveComparisonRequest.class.getRecordComponents()).map(c -> c.getName()).toList());

        ComparisonDetail detail = service.save(save("Test", null, request(5L, 6L)), alice);
        ComparisonResult computed = backtests.compare(request(5L, 6L), alice);
        assertSameResult(computed, json.readValue(savedDb.get(detail.id()).getResultJson(), ComparisonResult.class));
    }

    @Test
    void aSavedComparisonGivesTheSameMetricsAsAComparisonRunAgainOnTheSameData() {
        Long id = service.save(save("Test", "SHARPE", request(5L, 6L)), alice).id();

        ComparisonDetail reread = service.findOne(id, alice);                   // relu depuis le JSON en base
        ComparisonResult rerun = backtests.compare(request(5L, 6L), alice);     // relance sur les memes cours

        assertTrue(rerun.strategies().get(0).metrics().tradeCount() > 0, "le jeu de donnees doit produire des trades");
        assertSameResult(rerun, reread.result());
    }

    @Test
    void theRankingDefaultsToSharpe() {
        assertEquals(ComparisonRanking.SHARPE, service.save(save("Test", null, request(5L, 6L)), alice).rankBy());
        assertEquals(ComparisonRanking.SHARPE, service.save(save("Test", "  ", request(5L, 6L)), alice).rankBy());
        assertEquals(ComparisonRanking.MAX_DRAWDOWN,
                service.save(save("Test", "MAX_DRAWDOWN", request(5L, 6L)), alice).rankBy());
    }

    // ===================== Validation =====================

    @Test
    void invalidInputIsRejectedBeforeAnyCalculation() {
        assertTrue(messageOf(() -> service.save(null, alice)).contains("obligatoire"));
        assertTrue(messageOf(() -> service.save(save(null, null, request(5L, 6L)), alice)).contains("name"));
        assertTrue(messageOf(() -> service.save(save("   ", null, request(5L, 6L)), alice)).contains("name"));
        assertTrue(messageOf(() -> service.save(save("x".repeat(101), null, request(5L, 6L)), alice)).contains("100"));
        String ranking = messageOf(() -> service.save(save("Test", "BEST", request(5L, 6L)), alice));
        assertTrue(ranking.contains("rankBy") && ranking.contains("BEST"), ranking);

        verify(marketData, never()).series(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void theNameMayHaveExactlyOneHundredCharacters() {
        String name = "x".repeat(100);
        assertEquals(name, service.save(save(name, null, request(5L, 6L)), alice).name());
    }

    @Test
    void comparisonErrorsAreReportedAndNothingIsSaved() {
        assertTrue(messageOf(() -> service.save(save("Test", null, null), alice)).contains("obligatoire"));
        assertTrue(messageOf(() -> service.save(save("Test", null, request(5L)), alice)).contains("2 et 5"));
        // periode trop courte : erreur du moteur, commune a toutes les strategies
        CompareRequest tooShort = new CompareRequest(List.of(5L, 6L), "TEST", START, START.plusDays(10),
                null, null, null, null, null, null, null);
        assertTrue(messageOf(() -> service.save(save("Test", null, tooShort), alice)).contains("trop courte"));
        verify(repository, never()).save(any());
    }

    // ===================== Lire, renommer, supprimer =====================

    @Test
    void theListHasSummariesNewestFirstWithTheirStrategies() {
        Long first = service.save(save("Premiere", null, request(5L, 6L)), alice).id();
        Long second = service.save(save("Seconde", "MAX_DRAWDOWN", request(6L, 5L)), alice).id();

        List<ComparisonSummary> list = service.findMine(alice);

        assertEquals(List.of(second, first), list.stream().map(ComparisonSummary::id).toList());
        ComparisonSummary s = list.get(0);
        assertEquals("Seconde", s.name());
        assertEquals("TEST", s.symbol());
        assertEquals(START, s.from());
        assertEquals(START.plusDays(299), s.to());
        assertEquals(SPLIT, s.splitDate());
        assertEquals(ComparisonRanking.MAX_DRAWDOWN, s.rankBy());
        assertEquals(prices.hash(), s.dataHash());
        assertEquals(List.of("RSI standard", "Croisement 5/20"), s.strategies().stream().map(StrategySnapshot::name).toList());
        assertEquals(List.of(StrategyStatus.CURRENT, StrategyStatus.CURRENT), s.strategyStatus());

        ComparisonDetail detail = service.findOne(second, alice);
        assertEquals(detail.result().strategies().get(1).metrics().totalReturn(), s.strategies().get(1).totalReturn());
        assertEquals(detail.result().strategies().get(0).metrics().buyHoldReturn(), s.buyHoldReturn());
    }

    @Test
    void renamingTrimsTheNameAndKeepsTheResult() {
        ComparisonDetail saved = service.save(save("Ancien nom", null, request(5L, 6L)), alice);

        ComparisonSummary renamed = service.rename(saved.id(), new RenameRequest("  Nouveau nom  "), alice);

        assertEquals("Nouveau nom", renamed.name());
        assertEquals("Nouveau nom", service.findOne(saved.id(), alice).name());
        assertSameResult(saved.result(), service.findOne(saved.id(), alice).result());
    }

    @Test
    void renamingValidatesTheName() {
        Long id = service.save(save("Test", null, request(5L, 6L)), alice).id();
        assertTrue(messageOf(() -> service.rename(id, new RenameRequest(""), alice)).contains("obligatoire"));
        assertTrue(messageOf(() -> service.rename(id, new RenameRequest("x".repeat(101)), alice)).contains("100"));
        assertTrue(messageOf(() -> service.rename(id, null, alice)).contains("obligatoire"));
        assertEquals("Test", service.findOne(id, alice).name());
    }

    @Test
    void deletingRemovesTheComparison() {
        Long id = service.save(save("Test", null, request(5L, 6L)), alice).id();

        service.delete(id, alice);

        assertTrue(service.findMine(alice).isEmpty());
        assertThrows(NotFoundException.class, () -> service.findOne(id, alice));
        assertThrows(NotFoundException.class, () -> service.delete(id, alice));
        assertTrue(strategyDb.containsKey(5L) && strategyDb.containsKey(6L), "les strategies ne sont pas touchees");
    }

    @Test
    void anUnknownComparisonIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.findOne(999L, alice));
        assertThrows(NotFoundException.class, () -> service.rename(999L, new RenameRequest("x"), alice));
        assertThrows(NotFoundException.class, () -> service.delete(999L, alice));
    }

    // ===================== Isolation entre utilisateurs =====================

    @Test
    void eachUserOnlySeesTheirOwnComparisons() {
        Long id = service.save(save("Comparaison d'Alice", null, request(5L, 6L)), alice).id();

        assertTrue(service.findMine(bob).isEmpty());
        assertThrows(NotFoundException.class, () -> service.findOne(id, bob));
        assertThrows(NotFoundException.class, () -> service.rename(id, new RenameRequest("Pirate"), bob));
        assertThrows(NotFoundException.class, () -> service.delete(id, bob));

        // rien n'a change pour Alice
        assertEquals(List.of(id), service.findMine(alice).stream().map(ComparisonSummary::id).toList());
        assertEquals("Comparaison d'Alice", service.findOne(id, alice).name());
    }

    @Test
    void aUserCannotSaveAComparisonOfSomeoneElsesStrategies() {
        assertThrows(NotFoundException.class, () -> service.save(save("Test", null, request(5L, 6L)), bob));
        assertThrows(NotFoundException.class, () -> service.save(save("Test", null, request(5L, 9L)), alice));
        verify(repository, never()).save(any());
    }

    // ===================== Strategie supprimee ou modifiee ensuite =====================

    @Test
    void aComparisonStaysReadableAfterOneOfItsStrategiesIsDeleted() {
        ComparisonDetail saved = service.save(save("Test", null, request(5L, 6L)), alice);

        strategyDb.remove(6L);      // l'utilisateur supprime la strategie RSI dans l'onglet Strategies

        ComparisonDetail reread = service.findOne(saved.id(), alice);
        assertEquals(List.of(StrategyStatus.CURRENT, StrategyStatus.DELETED), reread.strategyStatus());
        StrategyComparison rsi = reread.result().strategies().get(1);
        assertEquals(6L, rsi.strategyId());
        assertEquals("RSI standard", rsi.strategyName());
        assertEquals(StrategyType.RSI, rsi.strategyType());
        assertEquals(14, rsi.params().getPeriod());
        assertSameResult(saved.result(), reread.result());       // courbes et metriques intactes

        ComparisonSummary summary = service.findMine(alice).get(0);
        assertEquals("RSI standard", summary.strategies().get(1).name());
        assertEquals(List.of(StrategyStatus.CURRENT, StrategyStatus.DELETED), summary.strategyStatus());
    }

    @Test
    void aStrategyModifiedAfterwardsIsFlaggedButNotARenamedOne() {
        Long id = service.save(save("Test", null, request(5L, 6L)), alice).id();

        strategyDb.get(5L).setParams(smaCross(10, 30));    // parametres changes
        strategyDb.get(6L).setName("RSI renomme");          // seul le nom change : resultats toujours valables
        assertEquals(List.of(StrategyStatus.MODIFIED, StrategyStatus.CURRENT), service.findOne(id, alice).strategyStatus());

        strategyDb.get(6L).setType(StrategyType.BOLLINGER); // type change
        assertEquals(List.of(StrategyStatus.MODIFIED, StrategyStatus.MODIFIED), service.findOne(id, alice).strategyStatus());
        assertEquals("RSI standard", service.findOne(id, alice).result().strategies().get(1).strategyName(),
                "le nom affiche reste celui de l'instantane");
    }

    // ===================== Verification des cours =====================

    @Test
    void aSavedComparisonStoresThePeriodHashAndCanBeChecked() {
        Long id = service.save(save("Test", null, request(5L, 6L)), alice).id();
        SavedComparison stored = savedDb.get(id);
        assertEquals(Prices.periodHash(Prices.normalized(prices), stored.getFromDate(), stored.getToDate()),
                stored.getPeriodHash());

        assertEquals(DataCheck.Status.SAME, service.dataCheck(id, alice).status());
        // cours ajustes depuis (dividende) : toutes les clotures baissent de 1 %
        PriceSeries adjusted = PriceSeries.of("TEST", "TEST", List.of(prices.dates()),
                java.util.Arrays.stream(prices.closes()).map(c -> c * 0.99).boxed().toList());
        when(marketData.series("TEST")).thenReturn(adjusted);
        assertEquals(DataCheck.Status.CHANGED, service.dataCheck(id, alice).status());
        assertThrows(NotFoundException.class, () -> service.dataCheck(id, bob));
    }

    @Test
    void aComparisonSavedBeforeTheseSettingsIsStillRead() {
        // meme reglage que le JsonMapper de Spring : un champ primitif absent est une erreur
        JsonMapper strict = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        SavedComparisonService strictService = new SavedComparisonService(repository, backtests, strategies, strict);
        Long id = service.save(save("Ancienne", null, request(5L, 6L)), alice).id();
        SavedComparison stored = savedDb.get(id);

        // JSON tel qu'enregistre avant le 2026-11-19 : sans taux sans risque, seances par an ni empreinte de periode
        tools.jackson.databind.node.ObjectNode result = (tools.jackson.databind.node.ObjectNode) strict.readTree(stored.getResultJson());
        ((tools.jackson.databind.node.ObjectNode) result.get("settings")).remove("riskFreeRate");
        result.remove("periodsPerYear");
        result.remove("periodHash");
        stored.setResultJson(result.toString());
        stored.setPeriodHash(null);

        ComparisonDetail detail = strictService.findOne(id, alice);

        assertEquals(0, detail.result().settings().riskFreeRate(), 0.0);
        assertNull(detail.result().periodsPerYear());
        assertNull(detail.result().periodHash());
        assertEquals(2, detail.result().strategies().size());
        assertEquals(DataCheck.Status.UNVERIFIABLE, strictService.dataCheck(id, alice).status());
    }

    @Test
    void anOldComparisonIsUnverifiable() {
        Long id = service.save(save("Test", null, request(5L, 6L)), alice).id();
        savedDb.get(id).setPeriodHash(null);       // enregistree avant l'empreinte de periode
        assertEquals(DataCheck.Status.UNVERIFIABLE, service.dataCheck(id, alice).status());
        verify(marketData, times(1)).series("TEST");   // seulement pour l'enregistrement, pas pour la verification
    }

    // ===================== Outils =====================

    private static void assertSameResult(ComparisonResult expected, ComparisonResult actual) {
        assertEquals(expected.symbol(), actual.symbol());
        assertEquals(expected.from(), actual.from());
        assertEquals(expected.to(), actual.to());
        assertEquals(expected.splitDate(), actual.splitDate());
        assertEquals(expected.dataSource(), actual.dataSource());
        assertEquals(expected.dataHash(), actual.dataHash());
        assertEquals(expected.settings(), actual.settings());
        assertArrayEquals(expected.dates(), actual.dates());
        assertArrayEquals(expected.buyHold(), actual.buyHold());
        assertEquals(expected.strategies().size(), actual.strategies().size());
        for (int i = 0; i < expected.strategies().size(); i++) {
            StrategyComparison e = expected.strategies().get(i);
            StrategyComparison a = actual.strategies().get(i);
            assertEquals(e.strategyId(), a.strategyId());
            assertEquals(e.strategyName(), a.strategyName());
            assertEquals(e.strategyType(), a.strategyType());
            assertEquals(e.metrics(), a.metrics());
            assertEquals(e.train(), a.train());
            assertEquals(e.test(), a.test());
            assertArrayEquals(e.curves().equity(), a.curves().equity());
            assertArrayEquals(e.curves().drawdown(), a.curves().drawdown());
        }
    }

    private static String messageOf(Runnable call) {
        return assertThrows(IllegalArgumentException.class, call::run).getMessage();
    }

    private static SaveComparisonRequest save(String name, String rankBy, CompareRequest request) {
        return new SaveComparisonRequest(name, rankBy, request);
    }

    private static CompareRequest request(Long... ids) {
        return new CompareRequest(List.of(ids), "TEST", null, null, 5000.0, 0.002, 0.001, 0.05, 0.10, 0.8, SPLIT);
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private static StrategyParams smaCross(int fast, int slow) {
        StrategyParams p = new StrategyParams();
        p.setFastPeriod(fast);
        p.setSlowPeriod(slow);
        return p;
    }

    /** Tendance haussiere avec des oscillations : de nombreux croisements (meme jeu que BacktestServiceTest). */
    private static PriceSeries wavy(int n) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dates.add(START.plusDays(i));
            closes.add(100 + 0.2 * i + 5 * Math.sin(i / 4.0));
        }
        return PriceSeries.of("TEST", "TEST", dates, closes);
    }

    private void strategy(Long id, String name, StrategyType type, StrategyParams params, User owner) {
        Strategy s = new Strategy();
        s.setId(id);
        s.setName(name);
        s.setType(type);
        s.setParams(params);
        s.setOwner(owner);
        strategyDb.put(id, s);
    }

    /** StrategyService simule : chaque utilisateur ne trouve que ses strategies (404 sinon), comme le vrai. */
    private void fakeStrategies() {
        when(strategies.findOne(anyLong(), any(User.class))).thenAnswer(call -> {
            Strategy s = strategyDb.get(call.<Long>getArgument(0));
            User u = call.getArgument(1);
            if (s == null || !s.getOwner().getId().equals(u.getId())) {
                throw new NotFoundException("Strategie introuvable");
            }
            return s;
        });
        when(strategies.findMine(any(User.class))).thenAnswer(call -> strategyDb.values().stream()
                .filter(s -> s.getOwner().getId().equals(call.<User>getArgument(0).getId())).toList());
    }

    /** Depot simule en memoire : identifiants croissants, filtre par proprietaire, plus recent d'abord. */
    private void fakeRepository() {
        when(repository.save(any(SavedComparison.class))).thenAnswer(call -> {
            SavedComparison c = call.getArgument(0);
            if (c.getId() == null) {
                c.setId(nextId++);
                c.onCreate();
            }
            savedDb.put(c.getId(), c);
            return c;
        });
        when(repository.findByIdAndOwnerId(anyLong(), anyLong())).thenAnswer(call -> Optional
                .ofNullable(savedDb.get(call.<Long>getArgument(0)))
                .filter(c -> c.getOwner().getId().equals(call.<Long>getArgument(1))));
        when(repository.rows(anyLong())).thenAnswer(call -> savedDb.values().stream()
                .filter(c -> c.getOwner().getId().equals(call.<Long>getArgument(0)))
                .sorted(Comparator.comparing(SavedComparison::getId).reversed())
                .map(c -> new ComparisonRow(c.getId(), c.getName(), c.getSymbol(), c.getFromDate(), c.getToDate(),
                        c.getSplitDate(), c.getRankBy(), c.getCreatedAt(), c.getDataSource(), c.getDataHash(),
                        c.getBuyHoldReturn(), c.getStrategiesJson()))
                .toList());
        doAnswer(call -> savedDb.remove(call.<SavedComparison>getArgument(0).getId()))
                .when(repository).delete(any(SavedComparison.class));
    }
}
