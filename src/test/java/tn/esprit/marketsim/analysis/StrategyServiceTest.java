package tn.esprit.marketsim.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import tn.esprit.marketsim.analysis.backtest.BacktestRepository;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;

/** CRUD des strategies : type et parametres valides a l'enregistrement, suppression en cascade des backtests. */
class StrategyServiceTest {

    private StrategyRepository repository;
    private BacktestRepository backtests;
    private StrategyService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(StrategyRepository.class);
        backtests = mock(BacktestRepository.class);
        service = new StrategyService(repository, backtests);
        user = new User();
        user.setId(7L);
        when(repository.save(any(Strategy.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static Strategy strategy(String name, StrategyType type) {
        Strategy s = new Strategy();
        s.setName(name);
        s.setType(type);
        return s;
    }

    @Test
    void creatingATypedStrategyCompletesParametersAndGeneratesTheIndicatorsText() {
        Strategy input = strategy("Croisement", StrategyType.SMA_CROSS);
        input.setIndicators("texte libre ignore");
        StrategyParams p = new StrategyParams();
        p.setFastPeriod(10);
        p.setBandK(3.0);            // ne concerne pas ce type : efface
        input.setParams(p);

        Strategy saved = service.create(input, user);

        assertEquals(user, saved.getOwner());
        assertEquals(10, saved.getParams().getFastPeriod());
        assertEquals(50, saved.getParams().getSlowPeriod(), "valeur par defaut");
        assertNull(saved.getParams().getBandK());
        assertEquals("SMA(10),SMA(50)", saved.getIndicators());
    }

    @Test
    void aStrategyWithoutTypeIsStillAccepted() {
        // compatibilite : le front actuel cree { name, indicators: "RSI(14)" } sans type
        Strategy input = strategy("Ancienne", null);
        input.setIndicators("RSI(14)");

        Strategy saved = service.create(input, user);

        assertNull(saved.getType());
        assertNull(saved.getParams());
        assertEquals("RSI(14)", saved.getIndicators());
    }

    @Test
    void parametersWithoutTypeAreRejected() {
        Strategy input = strategy("Incoherente", null);
        StrategyParams p = new StrategyParams();
        p.setPeriod(14);
        input.setParams(p);

        assertThrows(IllegalArgumentException.class, () -> service.create(input, user));
        verify(repository, never()).save(any());
    }

    @Test
    void invalidParametersAreRejectedBeforeSaving() {
        Strategy input = strategy("Mauvaise", StrategyType.MACD);
        StrategyParams p = new StrategyParams();
        p.setFastPeriod(30);
        p.setSlowPeriod(10);
        input.setParams(p);

        assertThrows(IllegalArgumentException.class, () -> service.create(input, user));
        verify(repository, never()).save(any());
    }

    @Test
    void updateChangesTypeAndParameters() {
        Strategy existing = strategy("Avant", StrategyType.RSI);
        existing.setId(3L);
        when(repository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(existing));

        Strategy saved = service.update(3L, strategy("Apres", StrategyType.BOLLINGER), user);

        assertEquals("Apres", saved.getName());
        assertEquals(StrategyType.BOLLINGER, saved.getType());
        assertEquals(20, saved.getParams().getPeriod());
        assertEquals("BB(20,2)", saved.getIndicators());
    }

    @Test
    void deletingAStrategyDeletesItsBacktestsFirst() {
        Strategy existing = strategy("A supprimer", StrategyType.RSI);
        existing.setId(3L);
        when(repository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(existing));

        service.delete(3L, user);

        InOrder order = inOrder(backtests, repository);
        order.verify(backtests).deleteByStrategyId(3L);
        order.verify(repository).delete(existing);
    }

    @Test
    void theStrategyOfAnotherUserIsNotFound() {
        when(repository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.delete(3L, user));
        verify(backtests, never()).deleteByStrategyId(any());
    }
}
