package tn.esprit.marketsim.user.activity;

/** Correlation ID de la requete courante (genere par le serveur). */
public final class OperationContext {

    public static final String CORRELATION_HEADER = "X-Correlation-ID";

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private OperationContext() {
    }

    public static void setCorrelationId(String correlationId) {
        CORRELATION_ID.set(correlationId);
    }

    public static String getCorrelationId() {
        return CORRELATION_ID.get();
    }

    public static void clear() {
        CORRELATION_ID.remove();
    }
}
