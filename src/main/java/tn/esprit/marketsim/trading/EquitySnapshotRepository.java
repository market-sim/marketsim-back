package tn.esprit.marketsim.trading;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EquitySnapshotRepository extends JpaRepository<EquitySnapshot, Long> {

    List<EquitySnapshot> findByOwnerIdOrderByTakenAtAscIdAsc(Long ownerId);
}
