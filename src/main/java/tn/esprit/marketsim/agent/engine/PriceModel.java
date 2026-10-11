package tn.esprit.marketsim.agent.engine;

/** Modele stochastique utilise pour simuler les trajectoires de prix. */
public enum PriceModel {
    /** Mouvement brownien geometrique (Black-Scholes) : rendements log-normaux, marche aleatoire pure. */
    GBM,
    /** Diffusion avec sauts de Merton (1976) : GBM + krachs/sauts poissonniens -> queues epaisses. */
    JUMP_DIFFUSION,
    /**
     * Changement de regimes de Hamilton (1989) : une chaine de Markov cachee alterne marche haussier
     * (drift fort, volatilite basse) et baissier (drift negatif, volatilite haute). Les regimes
     * persistent, ce qui rend la tendance partiellement previsible - comme sur les vrais marches.
     */
    REGIME_SWITCHING
}
