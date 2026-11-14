package tn.esprit.marketsim.support;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {

    List<SupportMessage> findByTicketIdOrderByCreatedAtAsc(Long ticketId);

    SupportMessage findFirstByTicketIdOrderByCreatedAtDesc(Long ticketId);

    long countByAuthorIdAndCreatedAtAfter(Long authorId, Instant after);
}
