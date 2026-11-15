package tn.esprit.marketsim.market;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès à l'historique FinBERT du Module 5. */
public interface MarketNewsAnalysisRepository extends JpaRepository<MarketNewsAnalysis, Long> {

    /** Une dernière analyse réussie par article, limitée au modèle/version actifs et à la période publiée. */
    @Query("""
            select a from MarketNewsAnalysis a
            where lower(a.symbol) = lower(:symbol)
              and a.publishedAt >= :fromDate and a.publishedAt < :toDate
              and a.status = :status
              and a.modelName = :modelName and a.modelVersion = :modelVersion
              and a.id = (
                  select max(a2.id) from MarketNewsAnalysis a2
                  where a2.newsId = a.newsId and a2.status = :status
                    and a2.modelName = :modelName and a2.modelVersion = :modelVersion
              )
            order by a.publishedAt desc, a.id desc
            """)
    List<MarketNewsAnalysis> findLatestSuccessfulForPeriod(
            @Param("symbol") String symbol,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            @Param("status") MarketSentimentStatus status,
            @Param("modelName") String modelName,
            @Param("modelVersion") String modelVersion);
}
