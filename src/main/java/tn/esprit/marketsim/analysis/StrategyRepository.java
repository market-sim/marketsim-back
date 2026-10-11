package tn.esprit.marketsim.analysis;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyRepository extends JpaRepository<Strategy, Long> {
    List<Strategy> findByOwnerId(Long ownerId);

    Optional<Strategy> findByIdAndOwnerId(Long id, Long ownerId);
}
