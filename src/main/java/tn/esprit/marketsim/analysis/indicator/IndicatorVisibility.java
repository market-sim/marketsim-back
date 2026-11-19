package tn.esprit.marketsim.analysis.indicator;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Module 3 : indicateurs affiches par une configuration enregistree (quatre courbes sur le prix, deux panneaux).
 * Memes noms que les interrupteurs de l'onglet Analyse technique du front ({@code Visibility}).
 * Colonnes prefixees par {@code show_} pour ne pas se confondre avec les periodes du meme nom.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorVisibility {

    @Column(name = "show_sma_fast", nullable = false)
    private Boolean smaFast;

    @Column(name = "show_sma_slow", nullable = false)
    private Boolean smaSlow;

    @Column(name = "show_ema", nullable = false)
    private Boolean ema;

    @Column(name = "show_bollinger", nullable = false)
    private Boolean bollinger;

    @Column(name = "show_rsi", nullable = false)
    private Boolean rsi;

    @Column(name = "show_macd", nullable = false)
    private Boolean macd;
}
