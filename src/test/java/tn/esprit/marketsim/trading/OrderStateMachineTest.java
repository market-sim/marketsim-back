package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.trading.TradeOrder.Status;

/** Machine a etats des tickets (pas besoin de base de donnees). */
class OrderStateMachineTest {

    @Test
    void onlyDocumentedTransitionsAreAllowed() {
        Set<String> allowed = Set.of("PENDING>OPEN", "PENDING>CANCELLED", "PENDING>REJECTED", "OPEN>CLOSED");
        for (Status from : Status.values()) {
            for (Status to : Status.values()) {
                boolean expected = allowed.contains(from + ">" + to);
                assertEquals(expected, OrderStateMachine.canTransition(from, to), from + " → " + to);
            }
        }
    }

    @Test
    void terminalStatesCannotBeLeft() {
        for (Status terminal : EnumSet.of(Status.CLOSED, Status.CANCELLED, Status.REJECTED)) {
            assertTrue(OrderStateMachine.isTerminal(terminal));
            for (Status to : Status.values()) {
                TradeOrder o = order(terminal);
                assertThrows(ConflictException.class, () -> OrderStateMachine.transition(o, to));
                assertEquals(terminal, o.getStatus(), "statut inchange apres un refus");
            }
        }
        assertFalse(OrderStateMachine.isTerminal(Status.OPEN));
    }

    @Test
    void cancellingAnOpenPositionPointsToClose() {
        TradeOrder o = order(Status.OPEN);
        o.setId(7L);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> OrderStateMachine.transition(o, Status.CANCELLED));
        assertTrue(ex.getMessage().contains("/close"));
    }

    @Test
    void validTransitionChangesStatus() {
        TradeOrder o = order(Status.PENDING);
        OrderStateMachine.transition(o, Status.OPEN);
        assertEquals(Status.OPEN, o.getStatus());
        OrderStateMachine.transition(o, Status.CLOSED);
        assertEquals(Status.CLOSED, o.getStatus());
    }

    private static TradeOrder order(Status s) {
        TradeOrder o = new TradeOrder();
        o.setStatus(s);
        return o;
    }
}
