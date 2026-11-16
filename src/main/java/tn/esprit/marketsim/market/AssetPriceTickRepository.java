package tn.esprit.marketsim.market;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AssetPriceTickRepository extends JpaRepository<AssetPriceTick, Long> {

    List<AssetPriceTick> findBySymbolAndAtAfterOrderByAtAsc(String symbol, Instant after);

    Optional<AssetPriceTick> findFirstBySymbolAndAtGreaterThanEqualOrderByAtAsc(String symbol, Instant from);

    @Modifying
    @Transactional
    @Query("delete from AssetPriceTick t where t.at < :before")
    int purgeBefore(@Param("before") Instant before);
}
