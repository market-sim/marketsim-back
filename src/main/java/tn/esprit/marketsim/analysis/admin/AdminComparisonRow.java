package tn.esprit.marketsim.analysis.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;

import tn.esprit.marketsim.analysis.admin.AdminRows.Owner;
import tn.esprit.marketsim.analysis.comparison.ComparisonRanking;

/**
 * Module 3, administration : comparaison enregistree de n'importe quel utilisateur, sans le resultat complet.
 * Rempli directement par la requete de {@link AdminComparisonQueries}.
 */
public record AdminComparisonRow(Long id, Long ownerId, String ownerFirstName, String ownerLastName,
                                 String ownerEmail, String name, String symbol, LocalDate from, LocalDate to,
                                 LocalDateTime createdAt, ComparisonRanking rankBy, Double buyHoldReturn,
                                 String strategiesJson) {

    public Owner owner() {
        return Owner.of(ownerId, ownerFirstName, ownerLastName, ownerEmail);
    }
}
