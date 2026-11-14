package tn.esprit.marketsim.user.profile;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPreferencesRepository extends JpaRepository<UserPreferences, Long> {

    Optional<UserPreferences> findByPublicId(String publicId);

    Optional<UserPreferences> findByWebauthnHandle(String handle);

    /**
     * Cree la ligne si elle n'existe pas, sans erreur si une autre requete l'a creee en meme temps
     * (deux onglets qui chargent la page au meme moment).
     */
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO user_preferences
              (user_id, language, theme, in_app_progress, in_app_security, email_security, email_progress,
               public_profile, public_id, webauthn_handle, updated_at)
            VALUES (:userId, 'fr', 'system', true, true, true, false, false, :publicId, :handle, :now)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("publicId") String publicId,
                       @Param("handle") String handle, @Param("now") Instant now);
}
