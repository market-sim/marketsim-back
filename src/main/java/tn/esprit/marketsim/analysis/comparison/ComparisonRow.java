package tn.esprit.marketsim.analysis.comparison;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Module 3 : ligne lue par {@link SavedComparisonRepository#rows} pour la liste des comparaisons enregistrees.
 * Tout sauf le resultat complet (courbes), qui est volumineux. Usage interne : le front recoit un
 * {@link ComparisonDtos.ComparisonSummary}.
 */
public record ComparisonRow(Long id, String name, String symbol, LocalDate from, LocalDate to,
                            LocalDate splitDate, ComparisonRanking rankBy, LocalDateTime createdAt,
                            String dataSource, String dataHash, Double buyHoldReturn, String strategiesJson) {
}
