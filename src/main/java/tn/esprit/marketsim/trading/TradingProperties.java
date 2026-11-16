package tn.esprit.marketsim.trading;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Parametres du module 2 (prefixe {@code trading.*} dans application.properties). Chaque valeur a un defaut :
 * le module fonctionne meme si rien n'est configure. Instanciable directement dans les tests.
 */
@Component
@ConfigurationProperties(prefix = "trading")
@Getter
@Setter
public class TradingProperties {

    /** Frais par transaction (0,001 = 0,1 %), appliques a l'ouverture ET a la cloture. */
    private BigDecimal feeRate = new BigDecimal("0.001");

    /** Capital de depart d'un compte (reference du rendement total). */
    private BigDecimal initialCapital = new BigDecimal("100000");

    private Risk risk = new Risk();

    private Engine engine = new Engine();

    private Protection protection = new Protection();

    @Getter
    @Setter
    public static class Risk {
        /** Taille max d'un ordre en % de l'equity (notional / equity). */
        private double maxPositionPct = 25;
        /** Exposition brute max sur un meme symbole, en % de l'equity, apres l'ordre. */
        private double maxSymbolConcentrationPct = 40;
        /** Liquidites minimales a conserver apres l'ordre, en % de l'equity. */
        private double minCashReservePct = 5;
        /** Nombre max de tickets ouverts + en attente. */
        private int maxOpenPositions = 20;
        /** Correlation constante entre actifs pour la VaR parametrique de portefeuille. */
        private double defaultCorrelation = 0.5;
    }

    @Getter
    @Setter
    public static class Engine {
        /** Intervalle du balayage SL / TP / LIMIT / STOP (ms). */
        private long intervalMs = 2000;
        /** Desactive le balayage planifie (le balayage a la demande reste actif). */
        private boolean enabled = true;
        /** Delai avant le premier balayage (ms). */
        private long initialDelayMs = 10000;
    }

    /** Suggestion automatique de stop-loss / take-profit selon la volatilite. */
    @Getter
    @Setter
    public static class Protection {
        /** Distance du stop-loss en ecarts-types journaliers (k · σ_jour · prix d'entree). */
        private double sigmaMultiple = 2;
        /** Ratio gain / risque du take-profit (distance TP = ratio × distance SL). */
        private double rewardRisk = 2;
        /** Distance minimale du stop-loss en % du prix (evite un SL colle au prix sur un actif tres calme). */
        private double minDistancePct = 0.2;
    }
}
