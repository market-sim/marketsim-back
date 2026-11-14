package tn.esprit.marketsim.user.profile;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Photo de profil : image PNG 256 x 256 re-encodee par le serveur (metadonnees supprimees). */
@Entity
@Table(name = "user_avatars")
@Getter
@Setter
@NoArgsConstructor
public class UserAvatar {

    @Id
    private Long userId;

    /** cle aleatoire de l'adresse publique ; change a chaque nouvelle photo (pas de cache perime) */
    @Column(nullable = false, unique = true, length = 36)
    private String avatarKey;

    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] data;

    private Instant updatedAt;
}
