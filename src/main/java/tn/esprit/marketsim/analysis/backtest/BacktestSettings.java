package tn.esprit.marketsim.analysis.backtest;

/**
 * Module 3 : reglages d'un backtest. Tous les taux sont des fractions (0.001 = 0,1 %), comme dans le module 6.
 *
 * @param initialCapital   capital de depart
 * @param feeRate          frais preleves sur le montant de chaque ordre (achat et vente)
 * @param slippage         glissement de prix : on achete un peu plus cher et on vend un peu moins cher que la cloture
 * @param stopLoss         perte maximale toleree sur une position (null = pas de stop-loss)
 * @param takeProfit       gain a partir duquel on prend ses benefices (null = pas de take-profit)
 * @param positionFraction part des liquidites engagee a chaque achat (1 = tout le capital)
 */
public record BacktestSettings(double initialCapital, double feeRate, double slippage, Double stopLoss,
                               Double takeProfit, double positionFraction) {

    public static final double DEFAULT_CAPITAL = 10_000;
    /** 0,1 % par ordre : meme valeur que le module 6 (Backtester.COST). */
    public static final double DEFAULT_FEE_RATE = 0.001;
    public static final double DEFAULT_SLIPPAGE = 0;
    public static final double DEFAULT_POSITION_FRACTION = 1;
}
