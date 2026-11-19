package tn.esprit.marketsim.analysis.backtest;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Module 3 : trace d'une modification d'un parametre par defaut des simulations : qui, quand, quel champ,
 * ancienne et nouvelle valeur. Une ligne par champ modifie. Les valeurs sont gardees en texte (taux en
 * fractions, « aucun » pour un stop-loss ou un take-profit desactive).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class SimulationDefaultsChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    /** Identifiant de l'administrateur (sans cle etrangere : la trace reste si le compte est supprime). */
    private Long changedById;

    @Column(nullable = false, length = 255)
    private String changedByEmail;

    /** Nom du champ, ex. {@code feeRate}. */
    @Column(nullable = false, length = 32)
    private String field;

    @Column(length = 32)
    private String oldValue;

    @Column(length = 32)
    private String newValue;
}
