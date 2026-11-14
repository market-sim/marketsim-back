package tn.esprit.marketsim.trading;

import java.math.BigDecimal;

import tn.esprit.marketsim.trading.TradeOrder.Side;

/**
 * Regles comptables du modele « ticket / lot » (pures, sans Spring).
 * <pre>
 *   principal      = qte x prixEntree                         (arrondi a 2 decimales)
 *   fraisOuverture = principal x feeRate
 *   fraisCloture   = qte x prixSortie x feeRate
 *   pnl (LONG)     = (sortie - entree) x qte ;  pnl (SHORT) = (entree - sortie) x qte
 *
 *   Ouverture (LONG et SHORT, marge 100 %) : solde -= principal + fraisOuverture
 *   Cloture                                : solde += principal + pnl - fraisCloture
 *     (LONG : principal + pnl = qte x sortie ; SHORT : restitution de la marge + (entree - sortie) x qte)
 *
 *   Invariant : variation totale du solde = pnl - (fraisOuverture + fraisCloture)
 * </pre>
 * Un ticket {@code legacy} (ouvert avant le moteur) n'a jamais debite son principal : il n'est donc pas restitue.
 */
public final class TradeAccounting {

    private TradeAccounting() {
    }

    public static BigDecimal principal(BigDecimal quantity, BigDecimal price) {
        return Money.amount(quantity.multiply(price));
    }

    public static BigDecimal fee(BigDecimal quantity, BigDecimal price, BigDecimal feeRate) {
        return Money.amount(quantity.multiply(price).multiply(feeRate));
    }

    /** Montant debite a l'ouverture (principal + frais), identique pour LONG et SHORT. */
    public static BigDecimal openCost(BigDecimal quantity, BigDecimal price, BigDecimal feeRate) {
        return principal(quantity, price).add(fee(quantity, price, feeRate));
    }

    /** P&L brut (hors frais) d'un ticket entre l'entree et un prix donne. */
    public static BigDecimal pnl(Side side, BigDecimal entry, BigDecimal exit, BigDecimal quantity) {
        BigDecimal diff = exit.subtract(entry).multiply(quantity);
        return Money.amount(side == Side.BUY ? diff : diff.negate());
    }

    /** Capital bloque par le ticket a l'ouverture (0 pour un ticket legacy). */
    public static BigDecimal lockedCapital(TradeOrder o) {
        return o.isLegacy() ? Money.zero() : principal(o.getQuantity(), o.entryPrice());
    }

    /**
     * Valeur d'un ticket OPEN dans l'equity : capital bloque + P&L latent. LONG = qte x prixActuel ;
     * SHORT = marge + (entree - prixActuel) x qte.
     */
    public static BigDecimal openValue(TradeOrder o, BigDecimal mark) {
        return lockedCapital(o).add(pnl(o.getSide(), o.entryPrice(), mark, o.getQuantity()));
    }

    /** Resultat d'une cloture. */
    public record Close(BigDecimal pnl, BigDecimal closeFee, BigDecimal credit) {
    }

    public static Close close(TradeOrder o, BigDecimal exit, BigDecimal feeRate) {
        BigDecimal pnl = pnl(o.getSide(), o.entryPrice(), exit, o.getQuantity());
        BigDecimal closeFee = fee(o.getQuantity(), exit, feeRate);
        BigDecimal credit = lockedCapital(o).add(pnl).subtract(closeFee);
        return new Close(pnl, closeFee, credit);
    }
}
