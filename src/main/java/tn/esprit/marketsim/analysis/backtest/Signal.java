package tn.esprit.marketsim.analysis.backtest;

/** Module 3 : ce que la strategie veut faire a la cloture d'un jour (l'ordre est execute le lendemain). */
public enum Signal {
    /** Entrer en position (ignore si une position est deja ouverte). */
    BUY,
    /** Sortir de la position (ignore si aucune position n'est ouverte). */
    SELL,
    /** Ne rien changer. */
    HOLD
}
