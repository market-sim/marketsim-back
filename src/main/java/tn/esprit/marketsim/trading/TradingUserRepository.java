package tn.esprit.marketsim.trading;

import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import tn.esprit.marketsim.user.User;

/**
 * Acces au solde du compte depuis le module 2. Defini ici (et non dans le UserRepository du module 1) pour ne pas
 * modifier le module 1 : Spring Data accepte plusieurs repositories sur la meme entite.
 */
public interface TradingUserRepository extends Repository<User, Long> {

    /**
     * Charge le compte avec un verrou pessimiste (SELECT ... FOR UPDATE) : tous les mouvements de solde d'un meme
     * utilisateur sont serialises dans la transaction en cours.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    Optional<User> findById(Long id);
}
