package tn.esprit.marketsim.progression;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TraderProfileRepository extends JpaRepository<TraderProfile, Long> {

    Optional<TraderProfile> findByUserId(Long userId);
}
