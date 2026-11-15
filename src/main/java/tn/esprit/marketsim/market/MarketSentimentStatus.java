package tn.esprit.marketsim.market;

/** Statut explicite d'une tentative d'analyse FinBERT. */
public enum MarketSentimentStatus {
    ANALYZED,
    UNAVAILABLE,
    INSUFFICIENT_DATA,
    UNSUPPORTED_LANGUAGE
}
