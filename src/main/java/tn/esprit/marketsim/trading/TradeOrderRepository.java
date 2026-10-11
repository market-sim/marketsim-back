package tn.esprit.marketsim.trading;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {
    List<TradeOrder> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Optional<TradeOrder> findByIdAndOwnerId(Long id, Long ownerId);
}
