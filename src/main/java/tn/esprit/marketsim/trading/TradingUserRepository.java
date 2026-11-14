package tn.esprit.marketsim.trading;

import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import tn.esprit.marketsim.user.User;

/**
 * Acces au solde du compte depuis le module 2. Defini ici (et non dans le UserRepository du module 1) pour ne pas
 * modifier le module 1 : Spring Data accepte plusieurs repositories sur la meme entite.
 */
public interface TradingUserRepository extends Repository<User, Long> {

    /**
     * Charge le compte avec un verrou pessimiste en ecriture (PESSIMISTIC_WRITE = SELECT ... FOR UPDATE) : tous les
     * mouvements de solde d'un meme utilisateur sont serialises dans la transaction en cours.
     * <p>
     * Requete native volontaire : avec {@code @Lock(PESSIMISTIC_WRITE)}, Hibernate genere la syntaxe MySQL 8
     * {@code FOR UPDATE OF alias}, refusee par MariaDB 10.4 (XAMPP). {@code FOR UPDATE} simple marche partout.
     */
    @Query(value = "SELECT * FROM users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    Optional<User> findById(Long id);
}
