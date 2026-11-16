package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradingDtos.ProtectionSuggestion;

/**
 * Suggestion de stop-loss / take-profit selon la volatilite (calcul pur, sans Spring).
 * <ul>
 *   <li>distance du stop-loss : d = k · σ_jour · entree (au moins {@code minDistancePct} % du prix,
 *       au plus 50 %) ;</li>
 *   <li>LONG : SL = entree − d, TP = entree + R·d ; SHORT : SL = entree + d, TP = entree − R·d
 *       (R = ratio gain / risque, TP borne pour rester positif) ;</li>
 *   <li>perte maximale = d × quantite + frais d'entree et de sortie au niveau du SL.</li>
 * </ul>
 * Le resultat respecte toujours {@link OrderRules#validateProtection}.
 */
public final class ProtectionCalculator {

    static final double MAX_DISTANCE = 0.5;

    private ProtectionCalculator() {
    }

    public static ProtectionSuggestion suggest(String symbol, Side side, BigDecimal entry, BigDecimal quantity,
                                               double sigmaAnnual, double sigmaDaily, String source,
                                               BigDecimal feeRate, TradingProperties.Protection cfg) {
        if (entry == null || entry.signum() <= 0) throw new IllegalArgumentException("Prix d'entrée indisponible");
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("La quantité doit être strictement positive");
        double k = cfg.getSigmaMultiple() > 0 ? cfg.getSigmaMultiple() : 2;
        double rr = cfg.getRewardRisk() > 0 ? cfg.getRewardRisk() : 2;
        double sd = Double.isFinite(sigmaDaily) && sigmaDaily > 0 ? sigmaDaily : 0;

        double fraction = Math.max(k * sd, Math.max(0, cfg.getMinDistancePct()) / 100);
        fraction = Math.min(fraction, MAX_DISTANCE);
        // un SHORT ne peut pas viser un TP a 0 : on borne R·d a 90 % du prix
        double tpFraction = Math.min(rr * fraction, side == Side.SELL ? 0.9 : Double.MAX_VALUE);

        BigDecimal e = Money.price(entry);
        BigDecimal d = Money.price(e.multiply(BigDecimal.valueOf(fraction)));
        BigDecimal dTp = Money.price(e.multiply(BigDecimal.valueOf(tpFraction)));
        if (d.signum() <= 0) d = new BigDecimal("0.000001");
        if (dTp.signum() <= 0) dTp = new BigDecimal("0.000001");

        // niveaux arrondis a un pas lisible (2 decimales au-dessus de 10 $, 4 au-dessus de 1, sinon 6), toujours
        // en s'eloignant du prix d'entree : l'arrondi ne rapproche jamais le stop-loss
        int scale = e.compareTo(BigDecimal.TEN) >= 0 ? 2 : e.compareTo(BigDecimal.ONE) >= 0 ? 4 : Money.PRICE_SCALE;
        BigDecimal sl;
        BigDecimal tp;
        if (side == Side.BUY) {
            sl = Money.price(e.subtract(d).setScale(scale, RoundingMode.FLOOR));
            tp = Money.price(e.add(dTp).setScale(scale, RoundingMode.CEILING));
        } else {
            sl = Money.price(e.add(d).setScale(scale, RoundingMode.CEILING));
            tp = Money.price(e.subtract(dTp).setScale(scale, RoundingMode.FLOOR));
        }
        if (sl.signum() <= 0) sl = Money.price(new BigDecimal("0.000001"));
        // distances reelles apres arrondi
        d = sl.subtract(e).abs();
        dTp = tp.subtract(e).abs();
        OrderRules.validateProtection(side, e, sl, tp);

        BigDecimal q = Money.price(quantity);
        BigDecimal fee = feeRate == null ? BigDecimal.ZERO : feeRate;
        BigDecimal entryFee = e.multiply(q).multiply(fee);
        BigDecimal maxLoss = Money.amount(d.multiply(q).add(entryFee).add(sl.multiply(q).multiply(fee)));
        BigDecimal gain = Money.amount(dTp.multiply(q).subtract(entryFee).subtract(tp.multiply(q).multiply(fee)));

        double sdPct = round(sd * 100, 3);
        String src = "REAL".equals(source) ? "réelle (historique de cours)" : "estimée par classe d'actif (modèle)";
        String explanation = String.format(Locale.FRANCE,
                "Volatilité journalière %s : %.2f %%. Stop-loss à %.1f σ du prix d'entrée (%.2f %%), "
                        + "take-profit avec un ratio gain / risque de %.1f. Si le stop-loss est touché, "
                        + "la perte estimée est de %s $ (frais compris).",
                src, sd * 100, k, fraction * 100, rr, String.format(Locale.FRANCE, "%,.2f", maxLoss));
        return new ProtectionSuggestion(symbol, side, e, sl, tp, sdPct, round(sigmaAnnual * 100, 2),
                "REAL".equals(source) ? "REAL" : "MODEL", k, rr, maxLoss, gain, explanation);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
