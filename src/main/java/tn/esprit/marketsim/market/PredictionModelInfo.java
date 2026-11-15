package tn.esprit.marketsim.market;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Description d'un modèle de prédiction disponible pour le Module 5.
 */
@Getter
@AllArgsConstructor
public class PredictionModelInfo {
    private final String id;
    private final String name;
    private final String version;
    private final String description;
    private final boolean available;
}