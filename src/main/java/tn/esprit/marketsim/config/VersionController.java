package tn.esprit.marketsim.config;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Version de l'API, lue par le frontend : s'il attend une version plus recente, il affiche un bandeau
 * « relance le serveur » au lieu d'erreurs incomprehensibles (« No static resource ... »).
 * A augmenter a chaque ajout d'endpoints utilises par le frontend.
 */
@RestController
public class VersionController {

    public static final int API_LEVEL = 10;

    @GetMapping("/api/public/version")
    public Map<String, Integer> version() {
        return Map.of("apiLevel", API_LEVEL);
    }
}
