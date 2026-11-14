package tn.esprit.marketsim.user.profile;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.profile.TraderCardService.TraderCard;

/** Contenu public, sans connexion : photos de profil et cartes de trader partagees. */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final AvatarService avatars;
    private final TraderCardService cards;

    /** La cle change a chaque nouvelle photo : le navigateur peut garder l'image en cache longtemps. */
    @GetMapping("/avatars/{key}")
    public ResponseEntity<byte[]> avatar(@PathVariable String key) {
        if (!key.matches("[0-9a-f-]{36}")) return ResponseEntity.notFound().build();
        return avatars.image(key)
                .map(img -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(img))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/traders/{publicId}")
    public ResponseEntity<TraderCard> trader(@PathVariable String publicId) {
        if (!publicId.matches("[a-z0-9]{6,16}")) return ResponseEntity.notFound().build();
        return cards.publicCard(publicId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
