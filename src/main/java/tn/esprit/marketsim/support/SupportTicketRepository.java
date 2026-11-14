package tn.esprit.marketsim.support;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByUserIdOrderByLastMessageAtDesc(Long userId);

    List<SupportTicket> findTop300ByOrderByLastMessageAtDesc();

    long countByUserIdAndStatusNot(Long userId, String status);
}
