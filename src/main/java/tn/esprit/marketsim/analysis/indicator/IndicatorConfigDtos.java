package tn.esprit.marketsim.analysis.indicator;

import java.time.LocalDateTime;

/** Module 3 : objets echanges avec le front pour les configurations d'indicateurs. */
public final class IndicatorConfigDtos {

    private IndicatorConfigDtos() {
    }

    /**
     * Creation ou modification (remplace tout). Tous les champs sont obligatoires sauf la description.
     *
     * @param periods periodes des indicateurs (memes regles que {@code GET /api/analysis/indicators})
     * @param show    indicateurs affiches
     */
    public record IndicatorConfigRequest(String name, String description, PeriodsInput periods,
                                         IndicatorVisibility show) {
    }

    /**
     * Periodes saisies. En nombres decimaux (comme les parametres texte de l'endpoint) : une periode de 20.5
     * est ainsi refusee par la regle « entier » au lieu d'etre tronquee a 20 en silence.
     */
    public record PeriodsInput(Double smaFast, Double smaSlow, Double ema, Double rsi, Double macdFast,
                               Double macdSlow, Double macdSignal, Double bbPeriod, Double bbK) {
    }

    /** Configuration renvoyee au front. */
    public record IndicatorConfigView(Long id, String name, String description, IndicatorPeriods periods,
                                      IndicatorVisibility show, LocalDateTime createdAt, LocalDateTime updatedAt) {

        static IndicatorConfigView of(IndicatorConfig c) {
            return new IndicatorConfigView(c.getId(), c.getName(), c.getDescription(), c.getPeriods(), c.getShow(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }
}
