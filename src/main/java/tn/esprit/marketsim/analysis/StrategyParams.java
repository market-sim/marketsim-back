package tn.esprit.marketsim.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Module 3 : parametres d'une strategie. Chaque type n'utilise que certains champs (voir
 * {@link StrategyRules}) ; les autres restent {@code null} et n'apparaissent pas dans le JSON.
 * Enregistres dans la table de {@link Strategy} (une colonne {@code param_...} par champ).
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StrategyParams {

    /** Periode de la moyenne rapide (SMA_CROSS, EMA_CROSS, MACD). */
    @Column(name = "param_fast_period")
    private Integer fastPeriod;

    /** Periode de la moyenne lente (SMA_CROSS, EMA_CROSS, MACD). */
    @Column(name = "param_slow_period")
    private Integer slowPeriod;

    /** Periode de la ligne de signal (MACD). */
    @Column(name = "param_signal_period")
    private Integer signalPeriod;

    /** Periode de l'indicateur (RSI, BOLLINGER). */
    @Column(name = "param_period")
    private Integer period;

    /** RSI : on achete quand le RSI est sous ce seuil (survente). */
    @Column(name = "param_buy_below")
    private Double buyBelow;

    /** RSI : on vend quand le RSI est au-dessus de ce seuil (surachat). */
    @Column(name = "param_sell_above")
    private Double sellAbove;

    /** BOLLINGER : nombre d'ecarts-types entre la moyenne et les bandes. */
    @Column(name = "param_band_k")
    private Double bandK;
}
