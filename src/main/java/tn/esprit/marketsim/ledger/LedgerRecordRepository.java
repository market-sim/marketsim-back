package tn.esprit.marketsim.ledger;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerRecordRepository extends JpaRepository<LedgerRecord, Long> {

    List<LedgerRecord> findTop500ByBlockHeightIsNullOrderByIdAsc();

    List<LedgerRecord> findByBlockHeightOrderByLeafIndexAsc(Integer blockHeight);

    long countByBlockHeightIsNull();

    @Query("select r from LedgerRecord r where (:owner is null or r.ownerId = :owner) "
            + "and (:type is null or r.type = :type) and (:agent is null or r.agentId = :agent) order by r.id desc")
    List<LedgerRecord> search(@Param("owner") Long owner, @Param("type") String type, @Param("agent") Long agent,
                              Pageable page);

    @Query("select r.type, count(r) from LedgerRecord r where (:owner is null or r.ownerId = :owner) group by r.type")
    List<Object[]> countByType(@Param("owner") Long owner);
}
