package tn.esprit.marketsim.analysis.backtest;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Run;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Trade;

/**
 * Module 3 : metriques de performance et de risque d'un backtest. Fonctions pures.
 *
 * <p>Reutilise {@link Stats} du module 6 pour la moyenne, l'ecart-type et le drawdown maximum ;
 * n'ajoute que ce qui n'existe pas encore (Sortino, Calmar, duree du drawdown, statistiques de trades).
 * Le Sharpe est calcule ici (et non par {@link Stats#sharpe}, qui suppose 252 seances par an et un taux nul).
 * Tous les taux sont des fractions (0.12 = 12 %). Une metrique impossible a calculer vaut {@code null}.
 *
 * <p>Annualisation : le nombre de seances par an est estime a partir des dates reelles de l'actif
 * ({@link #periodsPerYear}) : environ 252 pour une action, 365 pour une cryptomonnaie qui cote tous les jours.
 * Taux sans risque : Sharpe et Sortino portent sur le rendement au-dessus de ce taux.
 */
public final class PerformanceMetrics {

    /** Nombre de seances de bourse par an, utilise quand l'historique est trop court pour l'estimer. */
    public static final int TRADING_DAYS = PathGenerator.TRADING_DAYS;

    /** Duree minimale d'historique (jours calendaires) pour estimer le nombre de seances par an. */
    static final int MIN_DAYS_FOR_ESTIMATE = 90;

    private PerformanceMetrics() {
    }

    // ===================== Annualisation et taux sans risque =====================

    /**
     * Nombre de seances par an d'apres les dates reelles : intervalles entre seances / annees calendaires,
     * arrondi a l'entier. Environ 252 pour une action (week-ends et jours feries exclus), 365 pour une
     * cryptomonnaie. {@value #TRADING_DAYS} si l'historique couvre moins de {@value #MIN_DAYS_FOR_ESTIMATE} jours.
     */
    public static int periodsPerYear(LocalDate[] dates) {
        if (dates.length < 2) return TRADING_DAYS;
        long days = ChronoUnit.DAYS.between(dates[0], dates[dates.length - 1]);
        if (days < MIN_DAYS_FOR_ESTIMATE) return TRADING_DAYS;
        return (int) Math.round((dates.length - 1) / (days / 365.25));
    }

    /**
     * Taux sans risque par seance, compose : (1 + taux annuel) ^ (1 / seances par an) - 1.
     * Ex. 2 % par an sur 252 seances : 1,02 ^ (1/252) - 1 = 0,0000786 par seance.
     */
    public static double riskFreePerPeriod(double annualRate, int periodsPerYear) {
        return Math.pow(1 + annualRate, 1.0 / periodsPerYear) - 1;
    }

    /**
     * @param annualizedReturn   rendement annualise sur la duree calendaire (CAGR)
     * @param volatility         volatilite annualisee des rendements journaliers
     * @param sharpe             rendement moyen au-dessus du taux sans risque / ecart-type, annualise
     * @param sortino            comme le Sharpe, mais ne penalise que les rendements sous le taux sans risque
     * @param calmar             rendement annualise / |drawdown maximum| (null si aucun drawdown)
     * @param maxDrawdown        pire perte depuis un plus haut (valeur negative)
     * @param maxDrawdownDays    jours calendaires entre le sommet et le retour a ce sommet (ou la fin de periode)
     * @param maxDrawdownRecovery date du retour au sommet (null si le sommet n'a pas ete retrouve)
     * @param tradeCount         nombre de trades (allers-retours achat puis vente)
     * @param winRate            part des trades gagnants (null si aucun trade)
     * @param profitFactor       somme des gains / somme des pertes (null s'il n'y a aucune perte)
     * @param averageWin         gain moyen d'un trade gagnant, en montant ; averageWinReturn : en taux
     * @param averageLoss        perte moyenne d'un trade perdant, en montant (negatif) ; averageLossReturn : en taux
     * @param averageHoldingDays duree moyenne de detention, en jours calendaires
     * @param exposure           part du temps passe investi
     * @param excessReturn       rendement total - rendement du buy and hold
     */
    public record Metrics(double finalCapital, double totalReturn, Double annualizedReturn, double volatility,
                          double sharpe, Double sortino, Double calmar,
                          double maxDrawdown, int maxDrawdownDays, LocalDate maxDrawdownPeak,
                          LocalDate maxDrawdownTrough, LocalDate maxDrawdownRecovery,
                          int tradeCount, Double winRate, Double profitFactor,
                          Double averageWin, Double averageLoss, Double averageWinReturn, Double averageLossReturn,
                          Double averageHoldingDays, double exposure, double buyHoldReturn, double excessReturn) {

        /** Copie arrondie pour l'affichage et le stockage. */
        public Metrics rounded() {
            return new Metrics(Rounding.round(finalCapital, 2), Rounding.round(totalReturn, 6),
                    Rounding.round(annualizedReturn, 6), Rounding.round(volatility, 6), Rounding.round(sharpe, 4),
                    Rounding.round(sortino, 4), Rounding.round(calmar, 4),
                    Rounding.round(maxDrawdown, 6), maxDrawdownDays, maxDrawdownPeak, maxDrawdownTrough,
                    maxDrawdownRecovery, tradeCount, Rounding.round(winRate, 6), Rounding.round(profitFactor, 4),
                    Rounding.round(averageWin, 2), Rounding.round(averageLoss, 2),
                    Rounding.round(averageWinReturn, 6), Rounding.round(averageLossReturn, 6),
                    Rounding.round(averageHoldingDays, 2), Rounding.round(exposure, 6),
                    Rounding.round(buyHoldReturn, 6), Rounding.round(excessReturn, 6));
        }
    }

    /** Episode du drawdown maximum : indices du sommet, du creux et du retour au sommet (null si pas de retour). */
    public record DrawdownEpisode(double depth, int peakIndex, int troughIndex, Integer recoveryIndex) {
    }

    /** Toutes les metriques d'une simulation, avec 252 seances par an et un taux sans risque nul. */
    public static Metrics compute(Run run) {
        return compute(run, TRADING_DAYS, 0);
    }

    /**
     * Toutes les metriques d'une simulation.
     *
     * @param periodsPerYear seances par an de l'actif ({@link #periodsPerYear})
     * @param riskFreeRate   taux sans risque annuel (0.02 = 2 %)
     */
    public static Metrics compute(Run run, int periodsPerYear, double riskFreeRate) {
        double[] equity = run.equity();
        LocalDate[] dates = run.dates();
        List<Trade> trades = run.trades();
        int last = equity.length - 1;

        double totalReturn = totalReturn(equity);
        Double annualized = annualizedReturn(totalReturn, dates[0], dates[last]);
        DrawdownEpisode dd = maxDrawdownEpisode(equity);
        boolean hasDrawdown = dd.depth() < 0;
        int ddEnd = dd.recoveryIndex() == null ? last : dd.recoveryIndex();
        double buyHoldReturn = totalReturn(run.buyHold());

        return new Metrics(equity[last], totalReturn, annualized, volatility(equity, periodsPerYear),
                sharpe(equity, periodsPerYear, riskFreeRate), sortino(equity, periodsPerYear, riskFreeRate),
                calmar(annualized, dd.depth()),
                dd.depth(),
                hasDrawdown ? (int) ChronoUnit.DAYS.between(dates[dd.peakIndex()], dates[ddEnd]) : 0,
                hasDrawdown ? dates[dd.peakIndex()] : null,
                hasDrawdown ? dates[dd.troughIndex()] : null,
                hasDrawdown && dd.recoveryIndex() != null ? dates[dd.recoveryIndex()] : null,
                trades.size(), winRate(trades), profitFactor(trades),
                averagePnl(trades, true), averagePnl(trades, false),
                averageReturn(trades, true), averageReturn(trades, false),
                averageHoldingDays(trades), exposure(trades, equity.length),
                buyHoldReturn, totalReturn - buyHoldReturn);
    }

    // ===================== Rendement =====================

    /** Rendements journaliers : r(i) = valeur(i) / valeur(i-1) - 1. */
    public static double[] dailyReturns(double[] equity) {
        double[] r = new double[Math.max(0, equity.length - 1)];
        for (int i = 1; i < equity.length; i++) r[i - 1] = equity[i] / equity[i - 1] - 1;
        return r;
    }

    public static double totalReturn(double[] equity) {
        return equity[equity.length - 1] / equity[0] - 1;
    }

    /**
     * Rendement annualise (CAGR) : (1 + rendement total) ^ (365,25 / jours calendaires) - 1.
     * On utilise les dates reelles : le resultat reste juste quel que soit le nombre de seances par an.
     */
    public static Double annualizedReturn(double totalReturn, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 1) return null;
        if (totalReturn <= -1) return -1.0;   // capital entierement perdu
        return Math.pow(1 + totalReturn, 365.25 / days) - 1;
    }

    // ===================== Risque =====================

    /** Volatilite annualisee avec 252 seances par an. */
    public static double volatility(double[] equity) {
        return volatility(equity, TRADING_DAYS);
    }

    /** Volatilite annualisee : ecart-type des rendements par seance x racine(seances par an). */
    public static double volatility(double[] equity, int periodsPerYear) {
        return Stats.std(dailyReturns(equity)) * Math.sqrt(periodsPerYear);
    }

    /**
     * Ratio de Sharpe annualise : moyenne des rendements au-dessus du taux sans risque / ecart-type des rendements
     * x racine(seances par an). Retirer une constante ne change pas l'ecart-type. Avec 252 seances et un taux nul,
     * meme valeur que {@link Stats#sharpe} du module 6. 0 si la courbe est trop courte ou sans variation.
     */
    public static double sharpe(double[] equity, int periodsPerYear, double riskFreeRate) {
        if (equity.length < 3) return 0;
        double[] r = dailyReturns(equity);
        double sd = Stats.std(r);
        if (sd < 1e-12) return 0;
        double excess = Stats.mean(r) - riskFreePerPeriod(riskFreeRate, periodsPerYear);
        return excess / sd * Math.sqrt(periodsPerYear);
    }

    /** Ratio de Sortino avec 252 seances par an et un taux sans risque nul. */
    public static Double sortino(double[] equity) {
        return sortino(equity, TRADING_DAYS, 0);
    }

    /**
     * Ratio de Sortino annualise, rendement cible = taux sans risque par seance (rf) :
     * moyenne de (r - rf) / ecart a la baisse x racine(seances par an).
     * Ecart a la baisse = racine de la moyenne des carres des (r - rf) negatifs (les autres jours comptent pour 0).
     */
    public static Double sortino(double[] equity, int periodsPerYear, double riskFreeRate) {
        double[] r = dailyReturns(equity);
        if (r.length < 2) return null;
        double rf = riskFreePerPeriod(riskFreeRate, periodsPerYear);
        double sum = 0;
        for (double v : r) {
            double below = v - rf;
            if (below < 0) sum += below * below;
        }
        double downside = Math.sqrt(sum / r.length);
        if (downside < 1e-12) return null;    // jamais sous le taux sans risque : ratio infini
        return (Stats.mean(r) - rf) / downside * Math.sqrt(periodsPerYear);
    }

    /** Ratio de Calmar : rendement annualise / |drawdown maximum|. */
    public static Double calmar(Double annualizedReturn, double maxDrawdown) {
        if (annualizedReturn == null || maxDrawdown >= 0) return null;
        return annualizedReturn / -maxDrawdown;
    }

    /** Courbe de drawdown : ecart (negatif ou nul) entre la valeur du jour et le plus haut atteint jusque-la. */
    public static double[] drawdownCurve(double[] equity) {
        double[] out = new double[equity.length];
        double peak = equity[0];
        for (int i = 0; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            out[i] = equity[i] / peak - 1;
        }
        return out;
    }

    /**
     * Drawdown maximum (meme valeur que {@link Stats#maxDrawdown}) avec ses dates : dernier jour au sommet,
     * jour du creux, puis premier jour ou la valeur retrouve ce sommet.
     */
    public static DrawdownEpisode maxDrawdownEpisode(double[] equity) {
        double depth = Stats.maxDrawdown(equity);
        if (depth >= 0) {
            return new DrawdownEpisode(0, 0, 0, null);
        }
        double peak = equity[0];
        int peakIndex = 0;
        int worstPeak = 0;
        int trough = 0;
        double worst = 0;
        for (int i = 0; i < equity.length; i++) {
            if (equity[i] >= peak) {          // ">=" : on retient le DERNIER jour passe au sommet
                peak = equity[i];
                peakIndex = i;
            }
            double dd = equity[i] / peak - 1;
            if (dd < worst) {
                worst = dd;
                worstPeak = peakIndex;
                trough = i;
            }
        }
        Integer recovery = null;
        for (int i = trough + 1; i < equity.length; i++) {
            if (equity[i] >= equity[worstPeak]) {
                recovery = i;
                break;
            }
        }
        return new DrawdownEpisode(depth, worstPeak, trough, recovery);
    }

    // ===================== Trades =====================

    /** Part des trades gagnants (resultat net de frais strictement positif). */
    public static Double winRate(List<Trade> trades) {
        if (trades.isEmpty()) return null;
        return (double) trades.stream().filter(t -> t.pnl() > 0).count() / trades.size();
    }

    /** Profit factor : somme des gains / somme des pertes (en valeur absolue). */
    public static Double profitFactor(List<Trade> trades) {
        double gains = trades.stream().mapToDouble(Trade::pnl).filter(p -> p > 0).sum();
        double losses = -trades.stream().mapToDouble(Trade::pnl).filter(p -> p < 0).sum();
        return losses == 0 ? null : (Double) (gains / losses);
    }

    /** Resultat moyen (en montant) des trades gagnants ({@code winners = true}) ou perdants. */
    public static Double averagePnl(List<Trade> trades, boolean winners) {
        var stats = trades.stream().mapToDouble(Trade::pnl).filter(p -> winners ? p > 0 : p < 0).summaryStatistics();
        return stats.getCount() == 0 ? null : (Double) stats.getAverage();
    }

    /** Rendement moyen (en taux) des trades gagnants ou perdants. */
    public static Double averageReturn(List<Trade> trades, boolean winners) {
        var stats = trades.stream().filter(t -> winners ? t.pnl() > 0 : t.pnl() < 0)
                .mapToDouble(Trade::returnRate).summaryStatistics();
        return stats.getCount() == 0 ? null : (Double) stats.getAverage();
    }

    /** Duree moyenne de detention, en jours calendaires. */
    public static Double averageHoldingDays(List<Trade> trades) {
        if (trades.isEmpty()) return null;
        return trades.stream().mapToInt(Trade::holdingDays).average().orElse(0);
    }

    /**
     * Temps passe investi : seances passees en position / seances de la periode. Une periode de n points
     * contient n-1 intervalles cloture a cloture ; un trade occupe {@code bars} intervalles.
     */
    public static double exposure(List<Trade> trades, int points) {
        if (points < 2) return 0;
        return (double) trades.stream().mapToInt(Trade::bars).sum() / (points - 1);
    }
}
