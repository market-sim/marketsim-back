package tn.esprit.marketsim.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerBlockRepository extends JpaRepository<LedgerBlock, Integer> {

    Optional<LedgerBlock> findTopByOrderByHeightDesc();

    List<LedgerBlock> findAllByOrderByHeightAsc();

    List<LedgerBlock> findAllByOrderByHeightDesc(Pageable page);
}
