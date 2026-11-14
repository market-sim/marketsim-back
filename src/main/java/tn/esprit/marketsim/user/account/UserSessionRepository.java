package tn.esprit.marketsim.user.account;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    List<UserSession> findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(Long userId);

    List<UserSession> findByUserId(Long userId);
}
