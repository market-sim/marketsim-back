package tn.esprit.marketsim.analysis.indicator;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Module 3 : periodes des indicateurs d'une configuration enregistree. Memes noms que les reglages de l'onglet
 * Analyse technique du front ({@code Periods}), pour que le JSON s'y applique tel quel.
 * Valeurs controlees par {@link IndicatorConfigService} avec les regles de {@link IndicatorRules}.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorPeriods {

    /** SMA courte (sur le graphique du prix). */
    @Column(name = "sma_fast", nullable = false)
    private Integer smaFast;

    /** SMA longue : plus longue que la SMA courte. */
    @Column(name = "sma_slow", nullable = false)
    private Integer smaSlow;

    @Column(name = "ema_period", nullable = false)
    private Integer ema;

    @Column(name = "rsi_period", nullable = false)
    private Integer rsi;

    /** MACD : EMA rapide, EMA lente (plus longue) et ligne de signal. */
    @Column(name = "macd_fast", nullable = false)
    private Integer macdFast;

    @Column(name = "macd_slow", nullable = false)
    private Integer macdSlow;

    @Column(name = "macd_signal", nullable = false)
    private Integer macdSignal;

    /** Bollinger : periode de la moyenne et multiplicateur k (nombre d'ecarts-types). */
    @Column(name = "bb_period", nullable = false)
    private Integer bbPeriod;

    @Column(name = "bb_k", nullable = false)
    private Double bbK;
}
