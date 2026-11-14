package tn.esprit.marketsim.progression;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TraderGameRepository extends JpaRepository<TraderGame, Long> {

    List<TraderGame> findTop20ByUserIdOrderByPlayedAtDesc(Long userId);

    List<TraderGame> findTop100ByUserIdOrderByPlayedAtDesc(Long userId);

    /** Une partie deja jouee sur ce marche (meme graine) : la rejouer ne compte plus, le futur est connu. */
    boolean existsByUserIdAndSeedAndSymbol(Long userId, long seed, String symbol);
}
