package tn.esprit.marketsim.analysis.fundamental;

/**
 * Module 3 : la source de donnees fondamentales ne peut pas repondre (reseau, delai depasse, erreur HTTP,
 * configuration manquante). Le message, en francais, est montre a l'utilisateur.
 */
public class SourceUnavailableException extends RuntimeException {

    public SourceUnavailableException(String message) {
        super(message);
    }
}
