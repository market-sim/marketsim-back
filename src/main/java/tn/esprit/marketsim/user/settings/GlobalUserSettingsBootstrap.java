package tn.esprit.marketsim.user.settings;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Cree la ligne des parametres globaux pendant l'initialisation du contexte, donc avant que
 * Tomcat n'accepte des requetes. Sans cela, au premier demarrage sur une base vide,
 * DataInitializer et les premieres requetes (/auth/registration-status, login...) pouvaient
 * inserer la ligne id=1 en meme temps : cle dupliquee et arret de l'application.
 */
@Component
@RequiredArgsConstructor
public class GlobalUserSettingsBootstrap implements SmartInitializingSingleton {

    private final GlobalUserSettingsService settingsService;

    @Override
    public void afterSingletonsInstantiated() {
        settingsService.getOrCreate();
    }
}
