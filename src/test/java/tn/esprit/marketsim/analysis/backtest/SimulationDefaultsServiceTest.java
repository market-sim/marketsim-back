package tn.esprit.marketsim.analysis.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService.ChangeView;
import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService.DefaultsRequest;
import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService.DefaultsView;
import tn.esprit.marketsim.user.User;

/**
 * Parametres par defaut des simulations : valeurs d'origine, modification, validation (celle du backtest),
 * trace de chaque modification. Les depots sont simules en memoire.
 */
class SimulationDefaultsServiceTest {

    private SimulationDefaultsService service;
    private SimulationDefaults row;                          // la ligne unique, null tant qu'elle n'existe pas
    private final List<SimulationDefaultsChange> log = new ArrayList<>();
    private User admin;

    @BeforeEach
    void setUp() {
        SimulationDefaultsRepository repository = mock(SimulationDefaultsRepository.class);
        SimulationDefaultsChangeRepository changes = mock(SimulationDefaultsChangeRepository.class);
        when(repository.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(row));
        when(repository.save(any(SimulationDefaults.class))).thenAnswer(call -> row = call.getArgument(0));
        when(changes.saveAll(any())).thenAnswer(call -> {
            for (SimulationDefaultsChange c : call.<Iterable<SimulationDefaultsChange>>getArgument(0)) {
                c.setId((long) log.size() + 1);
                log.add(c);
            }
            return log;
        });
        when(changes.findAllByOrderByChangedAtDescIdDesc(any(Pageable.class))).thenAnswer(call -> log.stream()
                .sorted(Comparator.comparing(SimulationDefaultsChange::getChangedAt)
                        .thenComparing(SimulationDefaultsChange::getId).reversed())
                .toList());
        service = new SimulationDefaultsService(repository, changes);
        admin = new User();
        admin.setId(1L);
        admin.setEmail("admin@test.local");
    }

    private static DefaultsRequest request(Double capital, Double fee, Double slippage, Double stopLoss, Double takeProfit,
                                           Double fraction, Double riskFree) {
        return new DefaultsRequest(capital, fee, slippage, stopLoss, takeProfit, fraction, riskFree);
    }

    private static String messageOf(Runnable call) {
        return assertThrows(IllegalArgumentException.class, call::run).getMessage();
    }

    @Test
    void withoutModificationTheOriginalValuesApply() {
        assertEquals(BacktestSettings.BUILT_IN, service.current());
        DefaultsView view = service.view();
        assertEquals(BacktestSettings.BUILT_IN, view.current());
        assertEquals(BacktestSettings.BUILT_IN, view.original());
        assertNull(view.updatedAt());
        assertEquals(new BacktestSettings(10_000, 0.001, 0, null, null, 1, 0.0), BacktestSettings.BUILT_IN);
    }

    @Test
    void anUpdateIsStoredAndEachChangedFieldIsTraced() {
        DefaultsView view = service.update(request(5000.0, 0.002, 0.0, 0.05, null, 1.0, 0.02), admin);

        BacktestSettings expected = new BacktestSettings(5000, 0.002, 0, 0.05, null, 1, 0.02);
        assertEquals(expected, view.current());
        assertEquals(expected, service.current());
        assertEquals("admin@test.local", view.updatedBy());
        assertNotNull(view.updatedAt());
        assertEquals(BacktestSettings.BUILT_IN, view.original(), "les valeurs d'origine restent consultables");

        // quatre champs changent : capital, frais, stop-loss, taux sans risque (glissement, take-profit et part identiques)
        assertEquals(List.of("initialCapital", "feeRate", "stopLoss", "riskFreeRate"),
                log.stream().map(SimulationDefaultsChange::getField).toList());
        SimulationDefaultsChange fee = log.get(1);
        assertEquals("0.001", fee.getOldValue());
        assertEquals("0.002", fee.getNewValue());
        assertEquals("aucun", log.get(2).getOldValue(), "stop-loss d'origine : aucun");
        assertEquals("0.05", log.get(2).getNewValue());
        assertEquals("10000", log.get(0).getOldValue());
        assertEquals("admin@test.local", fee.getChangedByEmail());
        assertEquals(1L, fee.getChangedById());
        assertEquals(view.updatedAt(), fee.getChangedAt());
    }

    @Test
    void anUpdateWithoutChangeLeavesNoTrace() {
        service.update(request(10_000.0, 0.001, 0.0, null, null, 1.0, 0.0), admin);
        assertTrue(log.isEmpty());
        assertNull(row, "la ligne n'est meme pas creee");
        assertNull(service.view().updatedAt());
    }

    @Test
    void theHistoryListsTheMostRecentChangesFirst() {
        service.update(request(5000.0, 0.001, 0.0, null, null, 1.0, 0.0), admin);
        service.update(request(5000.0, 0.001, 0.0, 0.1, null, 1.0, 0.0), admin);
        service.update(request(8000.0, 0.001, 0.0, 0.0, null, 1.0, 0.0), admin);   // 0 = stop-loss desactive

        List<ChangeView> history = service.history();

        // 1 : capital ; 2 : stop-loss active ; 3 : capital et stop-loss desactive. Plus recente d'abord.
        assertEquals(List.of("stopLoss", "initialCapital", "stopLoss", "initialCapital"),
                history.stream().map(ChangeView::field).toList());
        assertEquals("0.1", history.get(0).oldValue());
        assertEquals("aucun", history.get(0).newValue(), "0 dans la saisie = stop-loss desactive, trace « aucun »");
        assertEquals("5000", history.get(1).oldValue());
        assertEquals("8000", history.get(1).newValue());
        assertNull(service.current().stopLoss());
        assertEquals(8000, service.current().initialCapital(), 0.0);
    }

    @Test
    void theRulesAreThoseOfTheBacktest() {
        assertTrue(messageOf(() -> service.update(request(0.0, 0.001, 0.0, null, null, 1.0, 0.0), admin)).startsWith("initialCapital"));
        assertTrue(messageOf(() -> service.update(request(5000.0, 0.06, 0.0, null, null, 1.0, 0.0), admin)).startsWith("feeRate"));
        assertTrue(messageOf(() -> service.update(request(5000.0, 0.001, -0.01, null, null, 1.0, 0.0), admin)).startsWith("slippage"));
        assertTrue(messageOf(() -> service.update(request(5000.0, 0.001, 0.0, 1.0, null, 1.0, 0.0), admin)).startsWith("stopLoss"));
        assertTrue(messageOf(() -> service.update(request(5000.0, 0.001, 0.0, null, 11.0, 1.0, 0.0), admin)).startsWith("takeProfit"));
        assertTrue(messageOf(() -> service.update(request(5000.0, 0.001, 0.0, null, null, 1.5, 0.0), admin)).startsWith("positionFraction"));
        assertTrue(messageOf(() -> service.update(request(5000.0, 0.001, 0.0, null, null, 1.0, 0.25), admin)).startsWith("riskFreeRate"));
        // meme message que le backtest lui-meme
        BacktestDtos.BacktestRequest sameAsBacktest = new BacktestDtos.BacktestRequest(1L, "X", null, null, null, 0.06,
                null, null, null, null, null);
        assertEquals(messageOf(() -> BacktestService.settings(sameAsBacktest)),
                messageOf(() -> service.update(request(5000.0, 0.06, 0.0, null, null, 1.0, 0.0), admin)));
        assertTrue(log.isEmpty(), "rien n'est enregistre apres un refus");
    }

    @Test
    void everyValueExceptStopLossAndTakeProfitIsRequired() {
        assertTrue(messageOf(() -> service.update(request(null, 0.001, 0.0, null, null, 1.0, 0.0), admin)).startsWith("initialCapital"));
        assertTrue(messageOf(() -> service.update(request(5000.0, 0.001, 0.0, null, null, 1.0, null), admin)).startsWith("riskFreeRate"));
        assertTrue(messageOf(() -> service.update(null, admin)).contains("obligatoires"));
        assertEquals(BacktestSettings.BUILT_IN, service.current());
    }

    @Test
    void numbersAreTracedWithoutTrailingZeros() {
        assertEquals("0.001", SimulationDefaultsService.text(0.0010));
        assertEquals("10000", SimulationDefaultsService.text(10_000.0));
        assertEquals("aucun", SimulationDefaultsService.text(null));
    }
}
