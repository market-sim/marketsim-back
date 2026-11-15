package tn.esprit.marketsim.common;

/**
 * Levée quand l'historique réel disponible pour un instrument est insuffisant
 * pour entraîner / évaluer le modèle de prédiction.
 */
public class InsufficientDataException extends RuntimeException {
    public InsufficientDataException(String message) {
        super(message);
    }
}