package tn.esprit.marketsim.common;

/**
 * Levée quand le modèle de prédiction ne peut pas être entraîné ou exécuté
 * (données invalides, échec numérique, etc.).
 */
public class ModelUnavailableException extends RuntimeException {
    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}