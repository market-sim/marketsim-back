package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradingDtos.RiskAssessment;
import tn.esprit.marketsim.trading.TradingDtos.RiskCheck;
import tn.esprit.marketsim.trading.TradingDtos.RiskComponents;
import tn.esprit.marketsim.trading.TradingDtos.RiskLevel;
import tn.esprit.marketsim.trading.TradingDtos.RiskMetrics;

/**
 * Evaluation du risque d'un ordre AVANT execution (classe pure, deterministe, testable sans Spring).
 *
 * <h2>Score (0-100)</h2>
 * Somme ponderee de 5 composantes normalisees dans [0, 1] :
 * <pre>
 *   concentration (30) = min(1, concentrationApres% / maxConcentrationSymbole%)
 *   taille        (25) = min(1, (notional / equity)% / maxPosition%)
 *   volatilite    (20) = min(1, σ annuelle / 80 %)          (80 % ≈ crypto tres volatile)
 *   exposition    (15) = min(1, expositionBruteApres / equity)
 *   liquidite     (10) = min(1, max(0, 1 - liquiditesApres / equity))
 *   score = arrondi(30 c + 25 t + 20 v + 15 e + 10 l)
 * </pre>
 * Niveau : LOW &lt; 35 ≤ MEDIUM ≤ 65 &lt; HIGH ; BLOCKED si une regle dure echoue.
 *
 * <h2>Regles dures (ordre refuse)</h2>
 * solde ≥ notional + frais ; notional / equity ≤ max-position-pct ; exposition du symbole apres ordre / equity ≤
 * max-symbol-concentration-pct ; liquidites apres ordre / equity ≥ min-cash-reserve-pct ; tickets OPEN + PENDING
 * &lt; max-open-positions. Un ordre HIGH sans stop-loss produit un avertissement (non bloquant).
 *
 * <h2>VaR 95 % de l'ordre (1 jour)</h2>
 * Historique ({@link Stats#valueAtRisk}) sur les rendements reels si disponibles (signes inverses pour un SHORT),
 * sinon parametrique : 1,645 x σ journaliere x notional.
 */
public final class RiskCalculator {

    static final double W_CONCENTRATION = 30;
    static final double W_SIZE = 25;
    static final double W_VOLATILITY = 20;
    static final double W_EXPOSURE = 15;
    static final double W_LIQUIDITY = 10;
    static final double VOL_CAP = 0.80;
    static final double Z95 = 1.6448536269514722;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private RiskCalculator() {
    }

    /**
     * @param cash               liquidites du compte
     * @param equity             equity courante
     * @param symbolGrossBefore  exposition brute deja engagee sur ce symbole (LONG + SHORT, valeur de marche)
     * @param grossBefore        exposition brute totale du portefeuille
     * @param activeTickets      nombre de tickets OPEN + PENDING
     * @param sigmaAnnual        volatilite annualisee de l'actif
     * @param sortedDailyReturns rendements journaliers reels tries (null → VaR parametrique)
     */
    public record Input(Side side, BigDecimal quantity, BigDecimal referencePrice, BigDecimal feeRate, boolean hasStopLoss,
                        BigDecimal cash, BigDecimal equity, BigDecimal symbolGrossBefore, BigDecimal grossBefore,
                        int activeTickets, double sigmaAnnual, double[] sortedDailyReturns, String volatilitySource,
                        TradingProperties.Risk limits) {
    }

    public static RiskAssessment assess(Input in) {
        TradingProperties.Risk lim = in.limits();
        BigDecimal notional = TradeAccounting.principal(in.quantity(), in.referencePrice());
        BigDecimal fee = TradeAccounting.fee(in.quantity(), in.referencePrice(), in.feeRate());
        BigDecimal cost = notional.add(fee);
        BigDecimal equity = Money.amount(in.equity());
        BigDecimal cashAfter = Money.amount(in.cash()).subtract(cost);
        BigDecimal symbolAfter = Money.orZero(in.symbolGrossBefore()).add(notional);
        BigDecimal grossAfter = Money.orZero(in.grossBefore()).add(notional);

        BigDecimal orderPct = pct(notional, equity);
        BigDecimal concentrationPct = pct(symbolAfter, equity);
        BigDecimal grossPct = pct(grossAfter, equity);
        BigDecimal cashAfterPct = equity.signum() > 0 ? pct(cashAfter, equity) : BigDecimal.ZERO.setScale(2);

        List<RiskCheck> checks = new ArrayList<>();
        checks.add(check("BALANCE", in.cash().compareTo(cost) >= 0, true, cost, Money.amount(in.cash()),
                in.cash().compareTo(cost) >= 0 ? "Solde suffisant"
                        : "Solde insuffisant : " + cost.toPlainString() + " requis (notional + frais), "
                        + Money.amount(in.cash()).toPlainString() + " disponible"));
        checks.add(limitCheck("MAX_POSITION_SIZE", orderPct, lim.getMaxPositionPct(), true,
                "Taille de l'ordre %s %% de l'equity (max %s %%)"));
        checks.add(limitCheck("SYMBOL_CONCENTRATION", concentrationPct, lim.getMaxSymbolConcentrationPct(), true,
                "Exposition sur le symbole après ordre %s %% de l'equity (max %s %%)"));
        boolean reserveOk = equity.signum() > 0 && cashAfterPct.doubleValue() >= lim.getMinCashReservePct();
        checks.add(check("MIN_CASH_RESERVE", reserveOk, true, cashAfterPct, bd(lim.getMinCashReservePct()),
                String.format(Locale.FRANCE, "Liquidités après ordre %s %% de l'equity (min %s %%)",
                        cashAfterPct.toPlainString(), fmt(lim.getMinCashReservePct()))));
        boolean slotsOk = in.activeTickets() + 1 <= lim.getMaxOpenPositions();
        checks.add(check("MAX_OPEN_POSITIONS", slotsOk, true, BigDecimal.valueOf(in.activeTickets() + 1L),
                BigDecimal.valueOf(lim.getMaxOpenPositions()),
                String.format(Locale.FRANCE, "%d tickets ouverts / en attente après ordre (max %d)",
                        in.activeTickets() + 1, lim.getMaxOpenPositions())));

        // ----- Score -----
        double c = clamp(concentrationPct.doubleValue() / lim.getMaxSymbolConcentrationPct());
        double s = clamp(orderPct.doubleValue() / lim.getMaxPositionPct());
        double v = clamp(in.sigmaAnnual() / VOL_CAP);
        double g = clamp(grossPct.doubleValue() / 100.0);
        double l = equity.signum() > 0 ? clamp(1 - cashAfter.doubleValue() / equity.doubleValue()) : 1;
        int score = (int) Math.round(W_CONCENTRATION * c + W_SIZE * s + W_VOLATILITY * v + W_EXPOSURE * g + W_LIQUIDITY * l);
        score = Math.max(0, Math.min(100, score));

        boolean blocked = checks.stream().anyMatch(k -> k.blocking() && !k.passed());
        RiskLevel level = blocked ? RiskLevel.BLOCKED : score < 35 ? RiskLevel.LOW : score <= 65 ? RiskLevel.MEDIUM : RiskLevel.HIGH;
        if (level == RiskLevel.HIGH && !in.hasStopLoss()) {
            checks.add(check("STOP_LOSS_ADVISED", false, false, null, null,
                    "Risque élevé sans stop-loss : ajoutez un stop-loss pour limiter la perte"));
        }

        BigDecimal var95 = orderVar95(in.side(), notional, in.sigmaAnnual(), in.sortedDailyReturns());
        RiskMetrics metrics = new RiskMetrics(in.referencePrice(), notional, fee, equity, orderPct, concentrationPct,
                grossPct, cashAfter, cashAfterPct, BigDecimal.valueOf(in.sigmaAnnual() * 100).setScale(2, RoundingMode.HALF_UP),
                in.volatilitySource(), var95);
        return new RiskAssessment(score, level, checks, metrics, new RiskComponents(round4(c), round4(s), round4(v),
                round4(g), round4(l)));
    }

    /** VaR 95 % a 1 jour de l'ordre seul, en devise (positive = perte). */
    static BigDecimal orderVar95(Side side, BigDecimal notional, double sigmaAnnual, double[] sortedReturns) {
        double loss;
        if (sortedReturns != null && sortedReturns.length > 0) {
            double[] r = sortedReturns;
            if (side == Side.SELL) {
                r = Arrays.stream(sortedReturns).map(x -> -x).sorted().toArray();
            }
            loss = Math.max(0, Stats.valueAtRisk(r, 0.95)) * notional.doubleValue();
        } else {
            loss = Z95 * sigmaAnnual / Math.sqrt(252) * notional.doubleValue();
        }
        return BigDecimal.valueOf(loss).setScale(2, RoundingMode.HALF_UP);
    }

    // ===================== Risque de portefeuille =====================

    static final double Z99 = 2.3263478740408408;
    /** CVaR 95 % d'une loi normale = σ x φ(1,645) / 5 %. */
    static final double ES95_FACTOR = 2.0627128075074257;

    /**
     * σ journaliere (en devise) du P&L du portefeuille, methode parametrique a correlation constante :
     * σp² = Σi Σj ρij ei ej σi σj, avec ρii = 1 et ρij = rho. ei = exposition signee (LONG +, SHORT -),
     * σi = volatilite journaliere de l'actif i.
     */
    public static double parametricSigma(double[] exposures, double[] dailySigmas, double rho) {
        double var = 0;
        for (int i = 0; i < exposures.length; i++) {
            for (int j = 0; j < exposures.length; j++) {
                double corr = i == j ? 1 : rho;
                var += corr * exposures[i] * exposures[j] * dailySigmas[i] * dailySigmas[j];
            }
        }
        return Math.sqrt(Math.max(0, var));
    }

    /**
     * P&L journaliers historiques du portefeuille actuel : Σi ei x ri,t sur les dates communes a tous les actifs
     * (au plus {@code window} jours). Tableau vide si les series ne se recouvrent pas assez.
     */
    public static double[] historicalPnl(double[] exposures, List<java.util.Map<java.time.LocalDate, Double>> closes,
                                         int window) {
        java.util.TreeSet<java.time.LocalDate> common = new java.util.TreeSet<>(closes.get(0).keySet());
        for (java.util.Map<java.time.LocalDate, Double> m : closes) common.retainAll(m.keySet());
        List<java.time.LocalDate> dates = new ArrayList<>(common);
        if (dates.size() < 2) return new double[0];
        int start = Math.max(1, dates.size() - window);
        double[] pnl = new double[dates.size() - start];
        for (int k = start; k < dates.size(); k++) {
            double total = 0;
            for (int i = 0; i < exposures.length; i++) {
                double prev = closes.get(i).get(dates.get(k - 1));
                double cur = closes.get(i).get(dates.get(k));
                total += exposures[i] * (cur / prev - 1);
            }
            pnl[k - start] = total;
        }
        return pnl;
    }

    /** Indice de Herfindahl-Hirschman des poids (expositions brutes), dans [0, 1]. */
    public static double hhi(double[] grossExposures) {
        double total = Arrays.stream(grossExposures).map(Math::abs).sum();
        if (total <= 0) return 0;
        double h = 0;
        for (double e : grossExposures) h += Math.pow(Math.abs(e) / total, 2);
        return h;
    }

    /** Score de diversification 0-100 : (1 - HHI) / (1 - 1/n) ; 0 avec une seule ligne. */
    public static double diversification(double hhi, int n) {
        if (n <= 1) return 0;
        return clamp((1 - hhi) / (1 - 1.0 / n)) * 100;
    }

    private static RiskCheck limitCheck(String name, BigDecimal valuePct, double maxPct, boolean blocking, String template) {
        boolean ok = valuePct.doubleValue() <= maxPct;
        return check(name, ok, blocking, valuePct, bd(maxPct),
                String.format(Locale.FRANCE, template, valuePct.toPlainString(), fmt(maxPct)));
    }

    private static RiskCheck check(String name, boolean passed, boolean blocking, BigDecimal value, BigDecimal limit,
                                   String message) {
        return new RiskCheck(name, passed, blocking, value, limit, message);
    }

    /** part / total en %, 2 decimales ; total ≤ 0 → 9999 (equity nulle ou negative : tout est hors limite). */
    static BigDecimal pct(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() <= 0) return new BigDecimal("9999.00");
        return part.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    private static double clamp(double x) {
        if (Double.isNaN(x)) return 1;
        return Math.max(0, Math.min(1, x));
    }

    private static double round4(double x) {
        return Math.round(x * 10_000) / 10_000.0;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
