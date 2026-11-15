package tn.esprit.marketsim.analysis.backtest;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Module 3 : moteur de backtesting pour strategies a regles, ecrit sans librairie de backtesting.
 * Achat uniquement (pas de vente a decouvert), une seule position a la fois.
 *
 * <h2>Deroulement d'une journee t (de la 2e a la derniere seance de la periode)</h2>
 * <ol>
 *   <li><b>Position ouverte</b> : on sort a la cloture de t si, dans cet ordre, le stop-loss est touche,
 *       le take-profit est touche, le signal de la veille est SELL, ou si t est le dernier jour.</li>
 *   <li><b>Pas de position</b> : si le signal de la veille est BUY, on achete a la cloture de t.</li>
 *   <li><b>Valorisation</b> : capital = liquidites + titres x cloture de t.</li>
 * </ol>
 *
 * <h2>Pas de biais d'anticipation</h2>
 * Le signal de la veille (jour t-1) n'utilise que les clotures connues a t-1 ; l'ordre est execute a la
 * cloture de t. Le premier jour de la periode sert seulement a observer : aucune execution.
 *
 * <h2>Prix et frais</h2>
 * Achat a cloture x (1 + glissement), vente a cloture x (1 - glissement). Les frais sont preleves sur le
 * montant de chaque ordre. A l'achat, on engage {@code positionFraction} des liquidites, frais compris.
 *
 * <h2>Limites connues (a dire en soutenance)</h2>
 * <ul>
 *   <li>Nous n'avons que les clotures : l'execution se fait a la cloture de J+1, alors qu'en pratique
 *       l'ordre partirait plutot a l'ouverture de J+1.</li>
 *   <li>Stop-loss et take-profit sont verifies sur les clotures, pas en cours de seance : la sortie se fait
 *       a la cloture du jour ou le seuil est franchi, donc parfois a un prix pire que le seuil (ou meilleur
 *       pour un take-profit), et un seuil touche puis repasse dans la journee n'est pas vu. Ce n'est pas de
 *       l'anticipation : un stop est un ordre deja place au moment de l'achat.</li>
 *   <li>Le dernier jour, la position encore ouverte est vendue pour que tous les trades soient complets ;
 *       aucun achat n'est fait ce jour-la.</li>
 *   <li>Liquidite supposee infinie et fractions de titres autorisees.</li>
 *   <li>Biais du survivant : on ne teste que des actifs qui existent encore aujourd'hui.</li>
 * </ul>
 */
public final class BacktestEngine {

    private BacktestEngine() {
    }

    /** Raison de la sortie d'une position. */
    public enum ExitReason { SIGNAL, STOP_LOSS, TAKE_PROFIT, END_OF_PERIOD }

    /**
     * Un aller-retour complet (achat puis vente).
     *
     * @param entryPrice  prix d'achat reel (glissement compris)
     * @param exitPrice   prix de vente reel (glissement compris)
     * @param invested    liquidites engagees a l'achat (frais d'achat compris)
     * @param pnl         resultat net : montant recupere a la vente (frais deduits) - invested
     * @param returnRate  pnl / invested
     * @param fees        frais d'achat + frais de vente
     * @param bars        nombre de seances entre l'achat et la vente
     * @param holdingDays nombre de jours calendaires entre l'achat et la vente
     */
    public record Trade(LocalDate entryDate, double entryPrice, LocalDate exitDate, double exitPrice,
                        double quantity, double invested, double pnl, double returnRate, double fees,
                        int bars, int holdingDays, ExitReason reason) {

        /** Copie arrondie pour l'affichage et le stockage (les calculs utilisent les valeurs exactes). */
        public Trade rounded() {
            return new Trade(entryDate, Rounding.round(entryPrice, 6), exitDate, Rounding.round(exitPrice, 6),
                    Rounding.round(quantity, 8), Rounding.round(invested, 2), Rounding.round(pnl, 2),
                    Rounding.round(returnRate, 6), Rounding.round(fees, 2), bars, holdingDays, reason);
        }
    }

    /**
     * Resultat brut d'une simulation. Les trois tableaux ont la meme taille : un point par seance de la periode.
     *
     * @param equity  valeur du portefeuille de la strategie a chaque cloture
     * @param buyHold valeur d'un portefeuille 100 % investi a la premiere cloture, sans frais (reference).
     *                La strategie BUY_AND_HOLD, elle, n'achete qu'a la 2e seance (execution a J+1) et paie des
     *                frais : son resultat differe donc de cette reference, en plus ou en moins selon la
     *                variation du premier jour.
     */
    public record Run(LocalDate[] dates, double[] equity, double[] buyHold, List<Trade> trades) {
    }

    /**
     * @param dates   dates de tout l'historique
     * @param closes  clotures de tout l'historique
     * @param signals un signal par jour de l'historique (voir {@link Signals})
     * @param lo      indice de la premiere seance de la periode testee
     * @param hi      indice de la derniere seance de la periode testee (incluse)
     */
    public static Run run(LocalDate[] dates, double[] closes, Signal[] signals, int lo, int hi, BacktestSettings s) {
        if (lo < 0 || hi >= closes.length || hi <= lo) {
            throw new IllegalArgumentException("Période de backtest invalide");
        }
        int n = hi - lo + 1;
        double[] equity = new double[n];
        double[] buyHold = new double[n];
        List<Trade> trades = new ArrayList<>();

        double cash = s.initialCapital();
        double units = 0;          // nombre de titres detenus
        double entryPrice = 0;
        double invested = 0;
        double entryFee = 0;
        int entryIndex = -1;

        equity[0] = cash;
        buyHold[0] = cash;

        for (int t = lo + 1; t <= hi; t++) {
            double close = closes[t];
            Signal yesterday = signals[t - 1];

            if (units > 0) {
                ExitReason reason = exitReason(close, entryPrice, yesterday, t == hi, s);
                if (reason != null) {
                    double price = close * (1 - s.slippage());
                    double gross = units * price;
                    double fee = gross * s.feeRate();
                    double proceeds = gross - fee;
                    cash += proceeds;
                    trades.add(new Trade(dates[entryIndex], entryPrice, dates[t], price, units, invested,
                            proceeds - invested, proceeds / invested - 1, entryFee + fee, t - entryIndex,
                            (int) ChronoUnit.DAYS.between(dates[entryIndex], dates[t]), reason));
                    units = 0;
                }
            } else if (yesterday == Signal.BUY && t < hi) {
                double price = close * (1 + s.slippage());
                invested = cash * s.positionFraction();
                entryFee = invested * s.feeRate();
                units = (invested - entryFee) / price;
                cash -= invested;
                entryPrice = price;
                entryIndex = t;
            }

            equity[t - lo] = cash + units * close;
            buyHold[t - lo] = s.initialCapital() * close / closes[lo];
        }
        return new Run(Arrays.copyOfRange(dates, lo, hi + 1), equity, buyHold, trades);
    }

    /** Raison de sortir aujourd'hui, ou null pour garder la position. */
    private static ExitReason exitReason(double close, double entryPrice, Signal yesterday, boolean lastDay,
                                         BacktestSettings s) {
        if (s.stopLoss() != null && close <= entryPrice * (1 - s.stopLoss())) return ExitReason.STOP_LOSS;
        if (s.takeProfit() != null && close >= entryPrice * (1 + s.takeProfit())) return ExitReason.TAKE_PROFIT;
        if (yesterday == Signal.SELL) return ExitReason.SIGNAL;
        if (lastDay) return ExitReason.END_OF_PERIOD;
        return null;
    }
}
