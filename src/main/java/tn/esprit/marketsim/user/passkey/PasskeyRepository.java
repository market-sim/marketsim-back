package tn.esprit.marketsim.user.passkey;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PasskeyRepository extends JpaRepository<PasskeyCredential, Long> {

    List<PasskeyCredential> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PasskeyCredential> findByCredentialId(String credentialId);

    long countByUserId(Long userId);
}
