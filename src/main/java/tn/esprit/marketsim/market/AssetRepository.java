package tn.esprit.marketsim.market;

import java.math.BigDecimal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    /** Mise a jour du cours seul (le moteur ne reecrit jamais nom / type modifies par l'admin entre-temps). */
    @Modifying
    @Transactional
    @Query("update Asset a set a.price = :price, a.changePct = :changePct where a.id = :id")
    int updateQuote(@Param("id") Long id, @Param("price") BigDecimal price, @Param("changePct") Double changePct);
}
