package tn.esprit.marketsim.analysis.comparison;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedComparisonRepository extends JpaRepository<SavedComparison, Long> {

    /** Comparaisons d'un utilisateur, de la plus recente a la plus ancienne, sans charger le resultat complet. */
    @Query("""
            select new tn.esprit.marketsim.analysis.comparison.ComparisonRow(
                c.id, c.name, c.symbol, c.fromDate, c.toDate, c.splitDate, c.rankBy, c.createdAt,
                c.dataSource, c.dataHash, c.buyHoldReturn, c.strategiesJson)
            from SavedComparison c
            where c.owner.id = :ownerId
            order by c.createdAt desc, c.id desc
            """)
    List<ComparisonRow> rows(@Param("ownerId") Long ownerId);

    Optional<SavedComparison> findByIdAndOwnerId(Long id, Long ownerId);
}
