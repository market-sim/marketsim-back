package tn.esprit.marketsim.trading;

import java.math.BigDecimal;

import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Side;

/** Regles de validation metier des ordres (pures, sans Spring). Erreur → IllegalArgumentException (400). */
public final class OrderRules {

    private OrderRules() {
    }

    /**
     * Coherence stop-loss / take-profit par rapport au prix d'entree de reference :
     * BUY (LONG) : SL &lt; entree &lt; TP ; SELL (SHORT) : TP &lt; entree &lt; SL. Un niveau absent n'est pas verifie.
     */
    public static void validateProtection(Side side, BigDecimal entry, BigDecimal stopLoss, BigDecimal takeProfit) {
        if (stopLoss != null && stopLoss.signum() <= 0) {
            throw new IllegalArgumentException("Le stop-loss doit être strictement positif");
        }
        if (takeProfit != null && takeProfit.signum() <= 0) {
            throw new IllegalArgumentException("Le take-profit doit être strictement positif");
        }
        if (entry == null) return;
        String e = entry.stripTrailingZeros().toPlainString();
        if (side == Side.BUY) {
            if (stopLoss != null && stopLoss.compareTo(entry) >= 0) {
                throw new IllegalArgumentException("Achat (LONG) : le stop-loss doit être inférieur au prix d'entrée " + e);
            }
            if (takeProfit != null && takeProfit.compareTo(entry) <= 0) {
                throw new IllegalArgumentException("Achat (LONG) : le take-profit doit être supérieur au prix d'entrée " + e);
            }
        } else {
            if (stopLoss != null && stopLoss.compareTo(entry) <= 0) {
                throw new IllegalArgumentException("Vente (SHORT) : le stop-loss doit être supérieur au prix d'entrée " + e);
            }
            if (takeProfit != null && takeProfit.compareTo(entry) >= 0) {
                throw new IllegalArgumentException("Vente (SHORT) : le take-profit doit être inférieur au prix d'entrée " + e);
            }
        }
    }

    /** LIMIT / STOP : limitPrice obligatoire et positif ; MARKET : limitPrice interdit (ignore sinon ambigu). */
    public static void validateType(OrderType type, BigDecimal limitPrice) {
        if (type == OrderType.MARKET) {
            if (limitPrice != null) {
                throw new IllegalArgumentException("Un ordre MARKET n'a pas de prix limite (le prix vient du serveur)");
            }
            return;
        }
        if (limitPrice == null || limitPrice.signum() <= 0) {
            throw new IllegalArgumentException("Un ordre " + type + " exige un prix limite / déclencheur (limitPrice) positif");
        }
    }

    /** Quantite strictement positive, 6 decimales max. */
    public static BigDecimal normalizeQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("La quantité doit être strictement positive");
        }
        if (quantity.stripTrailingZeros().scale() > Money.PRICE_SCALE) {
            throw new IllegalArgumentException("La quantité accepte au plus 6 décimales");
        }
        return Money.price(quantity);
    }
}
