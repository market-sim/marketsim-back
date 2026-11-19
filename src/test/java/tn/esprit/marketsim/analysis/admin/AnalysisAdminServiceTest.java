package tn.esprit.marketsim.analysis.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.admin.AdminRows.BacktestItem;
import tn.esprit.marketsim.analysis.admin.AdminRows.ComparisonItem;
import tn.esprit.marketsim.analysis.admin.AdminRows.ResultPage;
import tn.esprit.marketsim.analysis.backtest.BacktestService;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.StrategySnapshot;
import tn.esprit.marketsim.analysis.comparison.ComparisonRanking;
import tn.esprit.marketsim.analysis.comparison.SavedComparisonService;
import tn.esprit.marketsim.common.NotFoundException;

/** Administration : filtres, pagination, validation des parametres, detail et suppression. */
class AnalysisAdminServiceTest {

    private AdminBacktestQueries backtestQueries;
    private AdminComparisonQueries comparisonQueries;
    private BacktestService backtests;
    private SavedComparisonService comparisons;
    private AnalysisAdminService service;

    @BeforeEach
    void setUp() {
        backtestQueries = mock(AdminBacktestQueries.class);
        comparisonQueries = mock(AdminComparisonQueries.class);
        backtests = mock(BacktestService.class);
        comparisons = mock(SavedComparisonService.class);
        service = new AnalysisAdminService(backtestQueries, comparisonQueries, backtests, comparisons);
    }

    private static AdminBacktestRow backtestRow(long id) {
        return new AdminBacktestRow(id, 4L, "Alice", "Martin", "alice@test.local", 10L, "SMA", StrategyType.SMA_CROSS,
                "AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), LocalDateTime.of(2026, 11, 1, 9, 0),
                0.1, 0.1, 1.2, -0.1, 4, 0.2);
    }

    private static AdminComparisonRow comparisonRow(long id, String json) {
        return new AdminComparisonRow(id, 4L, "Alice", "Martin", "alice@test.local", "Comparaison " + id, "AAPL",
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), LocalDateTime.of(2026, 11, 1, 9, 0),
                ComparisonRanking.SHARPE, 0.2, json);
    }

    private static StrategySnapshot snapshot(StrategyType type) {
        return new StrategySnapshot(1L, type.name(), type, new StrategyParams(), 0.1, 0.1, 1.0, -0.1, 2);
    }

    @Test
    void backtestsAreFilteredByTheQueryAndPaged() {
        when(backtestQueries.page(eq(4L), eq("AAPL"), eq(StrategyType.RSI), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(backtestRow(7)), PageRequest.of(1, 20), 21));

        ResultPage<BacktestItem> page = service.backtests(4L, " AAPL ", "rsi", 1, 20);

        assertEquals(21, page.total());
        assertEquals(1, page.page());
        assertEquals(7L, page.items().get(0).id());
        assertEquals("Alice Martin", page.items().get(0).owner().name());
        assertEquals("alice@test.local", page.items().get(0).owner().email());
    }

    @Test
    void emptyFiltersMeanAll() {
        when(backtestQueries.page(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        assertEquals(0, service.backtests(null, "  ", "", 0, 50).total());
    }

    @Test
    void invalidParametersAreRejected() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.backtests(null, null, "MOMENTUM", 0, 50))
                .getMessage().startsWith("type"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.backtests(null, null, null, -1, 50))
                .getMessage().startsWith("page"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.comparisons(null, null, null, 0, 201))
                .getMessage().startsWith("size"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.stats(3)).getMessage().startsWith("weeks"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.stats(53)).getMessage().startsWith("weeks"));
    }

    @Test
    void comparisonsAreFilteredByStrategyTypeThenPaged() {
        when(comparisonQueries.rows(null, null)).thenReturn(List.of(
                comparisonRow(1, "a"), comparisonRow(2, "b"), comparisonRow(3, "c")));
        when(comparisons.snapshots("a")).thenReturn(List.of(snapshot(StrategyType.RSI), snapshot(StrategyType.MACD)));
        when(comparisons.snapshots("b")).thenReturn(List.of(snapshot(StrategyType.SMA_CROSS), snapshot(StrategyType.MACD)));
        when(comparisons.snapshots("c")).thenReturn(List.of(snapshot(StrategyType.RSI), snapshot(StrategyType.BOLLINGER)));

        ResultPage<ComparisonItem> rsi = service.comparisons(null, null, "RSI", 0, 50);
        assertEquals(List.of(1L, 3L), rsi.items().stream().map(ComparisonItem::id).toList());
        assertEquals(2, rsi.total());

        ResultPage<ComparisonItem> secondPage = service.comparisons(null, null, null, 1, 2);
        assertEquals(List.of(3L), secondPage.items().stream().map(ComparisonItem::id).toList());
        assertEquals(3, secondPage.total());
        assertEquals(List.of(), service.comparisons(null, null, null, 5, 2).items(), "page au-dela de la fin : vide");
    }

    @Test
    void detailAndDeletionReuseTheModuleServices() {
        when(backtestQueries.row(7L)).thenReturn(Optional.of(backtestRow(7)));
        assertEquals("Alice Martin", service.backtest(7L).owner().name());
        verify(backtests).findAny(7L);

        service.deleteBacktest(7L);
        verify(backtests).deleteAny(7L);
        service.deleteComparison(3L);
        verify(comparisons).deleteAny(3L);

        when(backtestQueries.row(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.backtest(99L));
        when(comparisonQueries.row(99L)).thenReturn(List.of());
        assertThrows(NotFoundException.class, () -> service.comparison(99L));
        verify(comparisons, never()).findAny(99L);
    }
}
