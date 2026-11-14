package tn.esprit.marketsim.trading;

import java.math.BigDecimal;

/**
 * Source unique du prix pour le module 2 : execution, SL/TP, risque et portefeuille passent tous par ici.
 * Le prix n'est JAMAIS lu dans la requete du client.
 */
public interface PriceProvider {

    /**
     * Prix courant du symbole (echelle 6).
     *
     * @throws IllegalArgumentException si le symbole n'existe pas dans le module 5 (→ 400)
     */
    BigDecimal currentPrice(String symbol);
}
