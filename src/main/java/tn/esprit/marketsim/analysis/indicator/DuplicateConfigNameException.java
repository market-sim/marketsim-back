package tn.esprit.marketsim.analysis.indicator;

/**
 * Module 3 : le nom choisi est deja celui d'une autre configuration du meme utilisateur. Devient un HTTP 409
 * ({@link IndicatorConfigExceptionHandler}) : le front affiche alors le message sous le champ Nom.
 */
public class DuplicateConfigNameException extends RuntimeException {

    public DuplicateConfigNameException(String name) {
        super("name : vous avez déjà une configuration nommée « " + name + " »");
    }
}
