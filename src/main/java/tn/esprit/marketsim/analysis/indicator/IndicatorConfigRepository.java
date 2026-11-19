package tn.esprit.marketsim.analysis.indicator;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IndicatorConfigRepository extends JpaRepository<IndicatorConfig, Long> {

    /** Configurations d'un utilisateur, par ordre alphabetique (liste deroulante de l'onglet). */
    List<IndicatorConfig> findByOwnerIdOrderByNameAsc(Long ownerId);

    Optional<IndicatorConfig> findByIdAndOwnerId(Long id, Long ownerId);

    /** Nom deja pris par une autre configuration du meme utilisateur (sans tenir compte des majuscules). */
    boolean existsByOwnerIdAndNameIgnoreCase(Long ownerId, String name);

    boolean existsByOwnerIdAndNameIgnoreCaseAndIdNot(Long ownerId, String name, Long id);
}
