package tn.esprit.marketsim.analysis.backtest;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Module 3 : parametres par defaut des simulations (backtest et comparaison), modifiables par l'administrateur.
 * Une seule ligne (identifiant {@value #ID}), creee a la premiere modification ; tant qu'elle n'existe pas, les
 * valeurs d'origine de {@link BacktestSettings#BUILT_IN} s'appliquent. Taux en fractions (0.001 = 0,1 %).
 * Chaque modification laisse une trace par champ dans {@link SimulationDefaultsChange}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class SimulationDefaults {

    /** Identifiant de l'unique ligne. */
    public static final long ID = 1;

    @Id
    private Long id;

    @Column(nullable = false)
    private Double initialCapital;

    @Column(nullable = false)
    private Double feeRate;

    @Column(nullable = false)
    private Double slippage;

    /** null = pas de stop-loss par defaut. */
    private Double stopLoss;

    /** null = pas de take-profit par defaut. */
    private Double takeProfit;

    @Column(nullable = false)
    private Double positionFraction;

    @Column(nullable = false)
    private Double riskFreeRate;

    private LocalDateTime updatedAt;

    /** E-mail de l'administrateur qui a fait la derniere modification (copie, au cas ou le compte disparait). */
    @Column(length = 255)
    private String updatedBy;

    /** Reglages correspondants. */
    public BacktestSettings toSettings() {
        return new BacktestSettings(initialCapital, feeRate, slippage, stopLoss, takeProfit, positionFraction, riskFreeRate);
    }
}
