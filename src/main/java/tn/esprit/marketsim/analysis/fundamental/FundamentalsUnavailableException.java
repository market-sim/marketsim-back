package tn.esprit.marketsim.analysis.fundamental;

/**
 * Module 3 : aucune donnee fondamentale a montrer (source injoignable et rien en cache). Devient une reponse
 * HTTP 503 avec un message en francais (voir {@link FundamentalsExceptionHandler}).
 */
public class FundamentalsUnavailableException extends RuntimeException {

    public FundamentalsUnavailableException(String message) {
        super(message);
    }
}
