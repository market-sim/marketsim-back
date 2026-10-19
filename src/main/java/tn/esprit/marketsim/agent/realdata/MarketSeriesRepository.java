package tn.esprit.marketsim.agent.realdata;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketSeriesRepository extends JpaRepository<MarketSeries, Long> {
    Optional<MarketSeries> findBySymbol(String symbol);
}
