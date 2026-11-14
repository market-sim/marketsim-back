package tn.esprit.marketsim.notification;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop30ByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    List<Notification> findByUserIdAndReadAtIsNull(Long userId);

    List<Notification> findTop200ByUserIdOrderByCreatedAtDesc(Long userId);

    List<Notification> findByUserIdAndReadAtIsNotNull(Long userId);
}
