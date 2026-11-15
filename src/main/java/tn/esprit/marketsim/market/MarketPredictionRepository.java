package tn.esprit.marketsim.market;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Module 5 — accès aux prédictions stockées. */
public interface MarketPredictionRepository extends JpaRepository<MarketPrediction, Long> {

    /** Toutes les prédictions, les plus récentes en premier. */
    List<MarketPrediction> findAllByOrderByGeneratedAtDesc();

    /** Prédictions filtrées par symbole d'actif (insensible à la casse). */
    List<MarketPrediction> findBySymbolIgnoreCaseOrderByGeneratedAtDesc(String symbol);

    /** Prédictions non encore résolues (outcome = null) — pour la tâche planifiée. */
    List<MarketPrediction> findByOutcomeIsNull();
}
