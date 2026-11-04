package tn.esprit.marketsim.market;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Module 5 — accès aux actualités financières stockées. */
public interface MarketNewsRepository extends JpaRepository<MarketNews, Long> {

    /** Toutes les actualités, les plus récentes en premier. */
    List<MarketNews> findAllByOrderByPublishedAtDesc();

    /** Actualités filtrées par symbole d'actif (insensible à la casse). */
    List<MarketNews> findBySymbolIgnoreCaseOrderByPublishedAtDesc(String symbol);
}
