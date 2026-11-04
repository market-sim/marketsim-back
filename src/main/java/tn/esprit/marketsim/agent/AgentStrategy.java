package tn.esprit.marketsim.agent;

/** Cerveau de l'agent. */
public enum AgentStrategy {
    /** Suit la tendance : momentum + croisement de moyennes mobiles (modele lineaire explicable). */
    MOMENTUM,
    /** Parie sur le retour a la moyenne : Bollinger + RSI (modele lineaire explicable). */
    MEAN_REVERSION,
    /** Politique apprise par renforcement (Q-learning tabulaire) sur des marches simules Monte Carlo. */
    Q_LEARNING
}
