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
 * @param riskFreeRate     taux sans risque annuel utilise par Sharpe et Sortino (0 = aucun). Type objet : les
 *                         backtests enregistres avant ce reglage n'ont pas ce champ dans leur JSON ; ils sont relus
 *                         avec 0, la valeur qu'ils utilisaient (Jackson refuse un champ primitif absent).
 */
public record BacktestSettings(double initialCapital, double feeRate, double slippage, Double stopLoss,
                               Double takeProfit, double positionFraction, Double riskFreeRate) {

    /** Un taux absent (ancien enregistrement) vaut 0. */
    public BacktestSettings {
        if (riskFreeRate == null) riskFreeRate = DEFAULT_RISK_FREE_RATE;
    }

    public static final double DEFAULT_CAPITAL = 10_000;
    /** 0,1 % par ordre : meme valeur que le module 6 (Backtester.COST). */
    public static final double DEFAULT_FEE_RATE = 0.001;
    public static final double DEFAULT_SLIPPAGE = 0;
    public static final double DEFAULT_POSITION_FRACTION = 1;
    public static final double DEFAULT_RISK_FREE_RATE = 0;
    /** Taux sans risque maximal accepte (20 % par an). */
    public static final double MAX_RISK_FREE_RATE = 0.20;

    /** Valeurs d'origine des parametres par defaut, tant que l'administrateur ne les a pas modifiees. */
    public static final BacktestSettings BUILT_IN = new BacktestSettings(DEFAULT_CAPITAL, DEFAULT_FEE_RATE,
            DEFAULT_SLIPPAGE, null, null, DEFAULT_POSITION_FRACTION, DEFAULT_RISK_FREE_RATE);

    /** Reglages sans taux sans risque (0 %). */
    public BacktestSettings(double initialCapital, double feeRate, double slippage, Double stopLoss,
                            Double takeProfit, double positionFraction) {
        this(initialCapital, feeRate, slippage, stopLoss, takeProfit, positionFraction, DEFAULT_RISK_FREE_RATE);
    }
}
