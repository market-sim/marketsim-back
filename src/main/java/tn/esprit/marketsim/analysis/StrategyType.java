package tn.esprit.marketsim.analysis;

/**
 * Module 3 : types de strategies a regles que le moteur de backtesting sait executer.
 * Les parametres de chaque type sont decrits dans {@link StrategyRules}.
 */
public enum StrategyType {
    /** Croisement de deux moyennes mobiles simples (rapide / lente). */
    SMA_CROSS,
    /** Croisement de deux moyennes mobiles exponentielles (rapide / lente). */
    EMA_CROSS,
    /** RSI : achat en zone de survente, vente en zone de surachat. */
    RSI,
    /** MACD : croisement de la ligne MACD avec sa ligne de signal. */
    MACD,
    /** Bollinger, retour a la moyenne : achat sous la bande basse, vente au retour a la moyenne. */
    BOLLINGER,
    /** Achat au debut de la periode, conservation jusqu'a la fin. */
    BUY_AND_HOLD
}
