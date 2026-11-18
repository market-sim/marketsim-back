package tn.esprit.marketsim.analysis.fundamental;

/**
 * Module 3 : source de donnees fondamentales. Les calculs (ratios, repere, cache) ne dependent que de cette
 * interface : changer de source (autre API, fichier...) revient a ecrire une autre implementation.
 */
public interface FundamentalsProvider {

    /** Nom court de la source, garde avec les donnees (ex. {@code SEC_EDGAR}). */
    String source();

    /**
     * Probleme de configuration qui empeche tout telechargement (ex. User-Agent absent), ou null si la source est
     * prete. Sert a prevenir l'utilisateur avant meme qu'il demande une action.
     */
    String configurationProblem();

    /**
     * Telecharge et extrait les donnees d'une action.
     *
     * @param symbol    symbole de l'action (ex. AAPL)
     * @param knownId   identifiant deja connu a la source (le CIK pour la SEC), ou null
     * @throws SourceUnavailableException source injoignable, en erreur ou non configuree
     * @throws tn.esprit.marketsim.common.NotFoundException la source ne connait pas cette societe
     */
    FundamentalsData fetch(String symbol, String knownId);

    /** Adresse du rapport publie a la source (pour le verifier soi-meme), ou null si la source n'en a pas. */
    String reportUrl(String companyId, String reportId);
}
