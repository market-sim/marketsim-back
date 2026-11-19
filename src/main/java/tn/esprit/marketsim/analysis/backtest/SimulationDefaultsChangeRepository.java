package tn.esprit.marketsim.analysis.backtest;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationDefaultsChangeRepository extends JpaRepository<SimulationDefaultsChange, Long> {

    /** Dernieres modifications, de la plus recente a la plus ancienne. */
    List<SimulationDefaultsChange> findAllByOrderByChangedAtDescIdDesc(Pageable page);
}
