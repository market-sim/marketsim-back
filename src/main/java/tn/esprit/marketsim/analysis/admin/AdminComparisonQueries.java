package tn.esprit.marketsim.analysis.admin;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import tn.esprit.marketsim.analysis.comparison.SavedComparison;

/**
 * Module 3, administration : lectures des comparaisons enregistrees de tous les utilisateurs, sans le resultat
 * complet. Le filtre par type de strategie porte sur l'instantane JSON : il est applique par le service.
 */
public interface AdminComparisonQueries extends Repository<SavedComparison, Long> {

    String ROW = """
            new tn.esprit.marketsim.analysis.admin.AdminComparisonRow(
                c.id, o.id, o.firstName, o.lastName, o.email, c.name, c.symbol, c.fromDate, c.toDate, c.createdAt,
                c.rankBy, c.buyHoldReturn, c.strategiesJson)""";

    /** Comparaisons, les plus recentes d'abord ; filtres facultatifs (null = tous). */
    @Query("select " + ROW + " from SavedComparison c join c.owner o"
            + " where (:ownerId is null or o.id = :ownerId) and (:symbol is null or c.symbol = :symbol)"
            + " order by c.createdAt desc, c.id desc")
    List<AdminComparisonRow> rows(@Param("ownerId") Long ownerId, @Param("symbol") String symbol);

    /** Une comparaison (pour connaitre son proprietaire). */
    @Query("select " + ROW + " from SavedComparison c join c.owner o where c.id = :id")
    List<AdminComparisonRow> row(@Param("id") Long id);
}
