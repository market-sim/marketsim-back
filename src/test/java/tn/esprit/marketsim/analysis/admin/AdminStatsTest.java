package tn.esprit.marketsim.analysis.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.admin.AdminRows.Owner;
import tn.esprit.marketsim.analysis.admin.AdminRows.UserActivity;
import tn.esprit.marketsim.analysis.admin.AdminStats.AssetUsage;
import tn.esprit.marketsim.analysis.admin.AdminStats.BestStrategy;
import tn.esprit.marketsim.analysis.admin.AdminStats.ComparisonFact;
import tn.esprit.marketsim.analysis.admin.AdminStats.Stats;
import tn.esprit.marketsim.analysis.admin.AdminStats.TypeUsage;
import tn.esprit.marketsim.analysis.admin.AdminStats.Week;

/**
 * Statistiques de l'administration sur un jeu de donnees connu.
 *
 * <pre>
 *   Aujourd'hui : mercredi 2026-11-18 (semaine du lundi 16/11).
 *   Alice (1) : b1 AAPL SMA_CROSS  Sharpe 1.0  2 ans  strategie 10   cree le 16/11
 *               b2 AAPL SMA_CROSS  Sharpe 2.0  6 mois strategie 10   cree le 17/11  (trop court pour le classement)
 *               b3 TSLA RSI        Sharpe 0.5  3 ans  strategie 11   cree le 09/11
 *   Bob (2)   : b4 AAPL MACD       Sharpe -0.4 1 an   strategie 12   cree le 02/11
 *               b5 BTC  RSI        Sharpe null 2 ans  strategie 13   cree il y a 20 semaines (hors fenetre)
 *   Comparaisons : c1 Alice AAPL [SMA_CROSS, RSI] le 18/11 ; c2 Bob TSLA [MACD, RSI, BUY_AND_HOLD] le 10/11
 * </pre>
 */
class AdminStatsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    private static final Owner ALICE = new Owner(1L, "Alice Martin", "alice@test.local");
    private static final Owner BOB = new Owner(2L, "Bob Durand", "bob@test.local");

    private static AdminBacktestRow backtest(long id, Owner owner, long strategyId, StrategyType type, String symbol,
                                             Double sharpe, LocalDate from, LocalDate to, LocalDateTime created) {
        String[] name = owner.name().split(" ");
        return new AdminBacktestRow(id, owner.id(), name[0], name[1], owner.email(), strategyId, "Strategie " + strategyId,
                type, symbol, from, to, created, 0.1, 0.05, sharpe, -0.2, 3, 0.15);
    }

    private static final List<AdminBacktestRow> BACKTESTS = List.of(
            backtest(1, ALICE, 10, StrategyType.SMA_CROSS, "AAPL", 1.0,
                    LocalDate.of(2023, 1, 1), LocalDate.of(2025, 1, 1), LocalDateTime.of(2026, 11, 16, 9, 0)),
            backtest(2, ALICE, 10, StrategyType.SMA_CROSS, "AAPL", 2.0,
                    LocalDate.of(2025, 1, 1), LocalDate.of(2025, 7, 1), LocalDateTime.of(2026, 11, 17, 9, 0)),
            backtest(3, ALICE, 11, StrategyType.RSI, "TSLA", 0.5,
                    LocalDate.of(2022, 1, 1), LocalDate.of(2025, 1, 1), LocalDateTime.of(2026, 11, 9, 9, 0)),
            backtest(4, BOB, 12, StrategyType.MACD, "AAPL", -0.4,
                    LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), LocalDateTime.of(2026, 11, 2, 9, 0)),
            backtest(5, BOB, 13, StrategyType.RSI, "BTC/USD", null,
                    LocalDate.of(2023, 1, 1), LocalDate.of(2025, 1, 1), LocalDateTime.of(2026, 7, 1, 9, 0)));

    private static final List<ComparisonFact> COMPARISONS = List.of(
            new ComparisonFact(ALICE, "AAPL", LocalDateTime.of(2026, 11, 18, 10, 0),
                    List.of(StrategyType.SMA_CROSS, StrategyType.RSI)),
            new ComparisonFact(BOB, "TSLA", LocalDateTime.of(2026, 11, 10, 10, 0),
                    List.of(StrategyType.MACD, StrategyType.RSI, StrategyType.BUY_AND_HOLD)));

    private static final Stats STATS = AdminStats.compute(BACKTESTS, COMPARISONS, TODAY, 4);

    @Test
    void totals() {
        assertEquals(5, STATS.totals().backtests());
        assertEquals(2, STATS.totals().comparisons());
        assertEquals(2, STATS.totals().users());
    }

    @Test
    void activityPerWeekFromMondayToSunday() {
        assertEquals(List.of(
                new Week(LocalDate.of(2026, 10, 26), 0, 0),
                new Week(LocalDate.of(2026, 11, 2), 1, 0),      // b4
                new Week(LocalDate.of(2026, 11, 9), 1, 1),      // b3, c2
                new Week(LocalDate.of(2026, 11, 16), 2, 1)),    // b1, b2, c1 ; b5 est hors fenetre
                STATS.weekly());
        assertEquals(LocalDate.of(2026, 11, 16), AdminStats.monday(LocalDate.of(2026, 11, 22)), "dimanche -> lundi precedent");
    }

    @Test
    void mostTestedAssets() {
        assertEquals(List.of(
                new AssetUsage("AAPL", 3, 1, 4),
                new AssetUsage("TSLA", 1, 1, 2),
                new AssetUsage("BTC/USD", 1, 0, 1)), STATS.assets());
    }

    @Test
    void strategyTypesAndAverageSharpe() {
        TypeUsage first = STATS.types().get(0);
        assertEquals(StrategyType.RSI, first.type(), "2 backtests + 2 comparaisons");
        assertEquals(2, first.backtests());
        assertEquals(2, first.inComparisons());
        assertEquals(0.5, first.averageSharpe(), "b5 sans Sharpe n'entre pas dans la moyenne");

        TypeUsage sma = STATS.types().stream().filter(t -> t.type() == StrategyType.SMA_CROSS).findFirst().orElseThrow();
        assertEquals(3, sma.total());
        assertEquals(1.5, sma.averageSharpe(), "(1.0 + 2.0) / 2");
        TypeUsage macd = STATS.types().stream().filter(t -> t.type() == StrategyType.MACD).findFirst().orElseThrow();
        assertEquals(-0.4, macd.averageSharpe());
        TypeUsage buyHold = STATS.types().stream().filter(t -> t.type() == StrategyType.BUY_AND_HOLD).findFirst().orElseThrow();
        assertEquals(1, buyHold.total());
        assertNull(buyHold.averageSharpe(), "aucun backtest de ce type");
        assertEquals(StrategyType.values().length, STATS.types().size(), "tous les types, meme inutilises");
        // RSI 4, SMA 3, MACD 2, buy & hold 1, puis EMA_CROSS et BOLLINGER (jamais utilises) dans l'ordre de l'enum
        assertEquals(List.of(StrategyType.RSI, StrategyType.SMA_CROSS, StrategyType.MACD, StrategyType.BUY_AND_HOLD,
                StrategyType.EMA_CROSS, StrategyType.BOLLINGER), STATS.types().stream().map(TypeUsage::type).toList());
    }

    @Test
    void mostActiveUsers() {
        List<UserActivity> users = STATS.users();
        assertEquals(ALICE, users.get(0).owner());
        assertEquals(3, users.get(0).backtests());
        assertEquals(1, users.get(0).comparisons());
        assertEquals(1.1667, users.get(0).averageSharpe(), "(1.0 + 2.0 + 0.5) / 3");
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 0), users.get(0).lastActivity(), "sa comparaison du 18/11");
        assertEquals(BOB, users.get(1).owner());
        assertEquals(-0.4, users.get(1).averageSharpe());
    }

    @Test
    void bestStrategiesUseBacktestsOfAtLeastOneYear() {
        List<BestStrategy> best = STATS.best();
        // strategie 10 : b2 (Sharpe 2.0) ne dure que 6 mois, son meilleur backtest retenu est b1 (1.0)
        assertEquals(List.of(1L, 3L, 4L), best.stream().map(BestStrategy::backtestId).toList());
        assertEquals(1.0, best.get(0).sharpe());
        assertEquals(ALICE, best.get(0).owner());
        assertEquals(StrategyType.MACD, best.get(2).strategyType(), "b4 dure exactement 366 jours (2024 bissextile)");
        // b5 n'a pas de Sharpe : absent
    }

    @Test
    void anEmptyBaseGivesEmptyStatistics() {
        Stats empty = AdminStats.compute(List.of(), List.of(), TODAY, 12);
        assertEquals(12, empty.weekly().size());
        assertEquals(0, empty.totals().users());
        assertEquals(List.of(), empty.assets());
        assertEquals(List.of(), empty.best());
    }
}
