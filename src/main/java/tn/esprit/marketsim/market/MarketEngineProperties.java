package tn.esprit.marketsim.market;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Parametres du moteur de prix du module 5 (prefixe {@code market.engine.*}). Chaque valeur a un defaut, et la
 * classe est instanciable directement dans les tests.
 */
@Component
@ConfigurationProperties(prefix = "market.engine")
@Getter
@Setter
public class MarketEngineProperties {

    /** Active le moteur (false = prix figes, comme avant). */
    private boolean enabled = true;

    /** Intervalle entre deux pas de prix (ms). */
    private long intervalMs = 3000;

    /** Delai avant le premier pas (ms). */
    private long initialDelayMs = 5000;

    /**
     * Acceleration du temps de marche : 60 = un pas de 3 s fait avancer le marche de 3 min. A 1, les prix
     * evoluent au rythme reel (variations de quelques centimes par minute : invisible en demonstration).
     */
    private double speed = 60;

    /** Age maximal d'un cours reel du bandeau pour servir d'ancre (ms). Au-dela : marche aleatoire libre. */
    private long anchorMaxAgeMs = 10 * 60 * 1000L;

    /**
     * Force de rappel vers le cours reel a chaque pas (0..1). Autour d'une ancre, l'ecart suit un processus
     * d'Ornstein-Uhlenbeck : il bouge a chaque pas mais revient vers le vrai cours.
     */
    private double reversion = 0.05;

    /** Ecart maximal autorise par rapport au cours reel (0,05 = ±5 %). */
    private double maxDeviation = 0.05;

    /** Rafraichit les cours reels en tache de fond (thread separe, appels reseau autorises). */
    private boolean refreshQuotes = true;

    /** Intervalle de rafraichissement des cours reels (ms). */
    private long refreshQuotesMs = 60_000;

    /** Un point d'historique par actif au plus toutes les {@code historyEveryMs} (ms). */
    private long historyEveryMs = 60_000;

    /** Duree de conservation de l'historique des cotations (jours). */
    private int retentionDays = 7;
}
