package tn.esprit.marketsim.user.account;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MailMessageRepository extends JpaRepository<MailMessage, Long> {

    List<MailMessage> findTop50ByOrderByCreatedAtDesc();

    List<MailMessage> findByRecipientOrderByCreatedAtDesc(String recipient);
}
