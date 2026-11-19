package tn.esprit.marketsim.analysis.comparison;

/**
 * Module 3 : critere de classement d'une comparaison (le meme choix que dans l'onglet Comparaison).
 * Il est enregistre avec la comparaison pour la rouvrir telle qu'elle etait affichee.
 */
public enum ComparisonRanking {
    /** Ratio de Sharpe : le plus eleve est le meilleur. */
    SHARPE,
    /** Rendement annualise : le plus eleve est le meilleur. */
    ANNUALIZED_RETURN,
    /** Drawdown maximum : le plus proche de zero est le meilleur. */
    MAX_DRAWDOWN
}
