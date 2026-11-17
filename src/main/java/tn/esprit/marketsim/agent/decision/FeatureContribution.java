package tn.esprit.marketsim.agent.decision;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Explication XAI : apport signe d'une variable a une decision de l'agent. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FeatureContribution {

    private String feature;

    private String label;

    /** Valeur observee de la variable (normalisee). */
    private double featureValue;

    /** Contribution au score : positive = pousse vers la decision / l'achat. */
    private double contribution;
}
