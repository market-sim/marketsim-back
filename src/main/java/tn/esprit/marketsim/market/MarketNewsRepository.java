package tn.esprit.marketsim.market;

import java.util.List;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

/** Module 5 — accès aux actualités financières stockées. */
public interface MarketNewsRepository extends JpaRepository<MarketNews, Long> {

    /** Toutes les actualités, les plus récentes en premier. */
    List<MarketNews> findAllByOrderByPublishedAtDesc();

    /** Actualités filtrées par symbole d'actif (insensible à la casse). */
    List<MarketNews> findBySymbolIgnoreCaseOrderByPublishedAtDesc(String symbol);

    /** Filtrage facultatif avec pagination, sans changer les méthodes historiques. */
    @Query("""
            select n from MarketNews n
            where (:symbol is null or lower(n.symbol) = lower(:symbol))
              and (:fromDate is null or n.publishedAt >= :fromDate)
              and (:toDate is null or n.publishedAt <= :toDate)
            order by n.publishedAt desc, n.id desc
            """)
    Page<MarketNews> search(@Param("symbol") String symbol,
                            @Param("fromDate") Instant fromDate,
                            @Param("toDate") Instant toDate,
                            Pageable pageable);

    boolean existsByUrlHash(String urlHash);
}
