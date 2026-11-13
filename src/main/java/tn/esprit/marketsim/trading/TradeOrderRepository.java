package tn.esprit.marketsim.trading;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {

    /** Utilise aussi par le module 1 (niveau du trader) et le module 6 (position de l'agent). */
    List<TradeOrder> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Optional<TradeOrder> findByIdAndOwnerId(Long id, Long ownerId);

    List<TradeOrder> findByOwnerIdAndStatus(Long ownerId, Status status);

    long countByOwnerIdAndStatusIn(Long ownerId, List<Status> statuses);

    @Query("select o.owner.id from TradeOrder o where o.id = :id")
    Optional<Long> findOwnerIdById(@Param("id") Long id);

    /** Tickets a surveiller par le moteur : LIMIT/STOP en attente, ou positions ouvertes avec SL/TP. */
    @Query("""
            select o.id from TradeOrder o
            where o.status = tn.esprit.marketsim.trading.TradeOrder.Status.PENDING
               or (o.status = tn.esprit.marketsim.trading.TradeOrder.Status.OPEN
                   and (o.stopLoss is not null or o.takeProfit is not null))
            order by o.id""")
    List<Long> findTriggerCandidateIds();

    @Query("""
            select o.id from TradeOrder o
            where o.owner.id = :ownerId
              and (o.status = tn.esprit.marketsim.trading.TradeOrder.Status.PENDING
                   or (o.status = tn.esprit.marketsim.trading.TradeOrder.Status.OPEN
                       and (o.stopLoss is not null or o.takeProfit is not null)))
            order by o.id""")
    List<Long> findTriggerCandidateIdsByOwner(@Param("ownerId") Long ownerId);

    /** Ticket le plus ancien d'un cote / symbole (cloture FIFO par l'agent). */
    Optional<TradeOrder> findFirstByOwnerIdAndSymbolIgnoreCaseAndSideAndStatusOrderByIdAsc(
            Long ownerId, String symbol, Side side, Status status);
}
