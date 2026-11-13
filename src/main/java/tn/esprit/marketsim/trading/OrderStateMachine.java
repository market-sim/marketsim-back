package tn.esprit.marketsim.trading;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.trading.TradeOrder.Status;

/**
 * Machine a etats centralisee des tickets. Seules transitions autorisees :
 * <pre>
 *   PENDING → OPEN | CANCELLED | REJECTED
 *   OPEN    → CLOSED
 *   CLOSED, CANCELLED, REJECTED : terminaux
 * </pre>
 * Toute autre transition leve une {@link ConflictException} (HTTP 409). Aucun autre code ne doit modifier le statut.
 */
public final class OrderStateMachine {

    private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            Status.PENDING, EnumSet.of(Status.OPEN, Status.CANCELLED, Status.REJECTED),
            Status.OPEN, EnumSet.of(Status.CLOSED),
            Status.CLOSED, EnumSet.noneOf(Status.class),
            Status.CANCELLED, EnumSet.noneOf(Status.class),
            Status.REJECTED, EnumSet.noneOf(Status.class));

    private OrderStateMachine() {
    }

    public static boolean canTransition(Status from, Status to) {
        return from != null && to != null && ALLOWED.get(from).contains(to);
    }

    public static boolean isTerminal(Status s) {
        return s == Status.CLOSED || s == Status.CANCELLED || s == Status.REJECTED;
    }

    /** Applique la transition ou refuse avec 409. */
    public static void transition(TradeOrder order, Status to) {
        Status from = order.getStatus();
        if (!canTransition(from, to)) {
            throw new ConflictException(message(order, from, to));
        }
        order.setStatus(to);
    }

    private static String message(TradeOrder order, Status from, Status to) {
        String ref = order.getId() == null ? "L'ordre" : "L'ordre #" + order.getId();
        if (from == Status.OPEN && to == Status.CANCELLED) {
            return ref + " est une position ouverte : utilisez /close pour la clôturer";
        }
        if (isTerminal(from)) {
            return ref + " est " + from + " (état terminal) : transition vers " + to + " impossible";
        }
        return ref + " : transition " + from + " → " + to + " interdite";
    }
}
