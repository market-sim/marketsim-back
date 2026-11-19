package tn.esprit.marketsim.analysis.indicator;

import tn.esprit.marketsim.analysis.StrategyRules;

/**
 * Module 3 : regles de validation des parametres d'indicateurs, communes a l'endpoint des indicateurs
 * ({@link IndicatorService}) et aux configurations enregistrees ({@link IndicatorConfigService}).
 *
 * <p>Fonctions pures. Une {@link IllegalArgumentException} devient une reponse HTTP 400. Les messages
 * commencent par le nom du parametre en cause (ex. {@code "macd : ..."}).
 * La regle qui depend de l'historique (periode plus courte que le nombre de seances) reste dans
 * {@link IndicatorService} : une configuration enregistree n'est liee a aucun actif.
 */
public final class IndicatorRules {

    /** Plus petite periode utile : une moyenne sur une seule seance n'est que le cours. */
    public static final int MIN_PERIOD = 2;

    /** Multiplicateur maximal des bandes de Bollinger (nombre d'ecarts-types). */
    public static final double MAX_BAND_K = 10;

    /**
     * Periode maximale d'une configuration enregistree : la meme que celle des strategies, pour qu'une
     * configuration puisse toujours servir a creer une strategie.
     */
    public static final int MAX_SAVED_PERIOD = StrategyRules.MAX_PERIOD;

    private IndicatorRules() {
    }

    /** Une periode est un entier superieur ou egal a {@value #MIN_PERIOD}. Refuse aussi NaN. */
    public static int period(String name, double value) {
        if (value != Math.rint(value) || value < MIN_PERIOD) {
            throw new IllegalArgumentException(name + " : la période doit être un entier supérieur ou égal à " + MIN_PERIOD);
        }
        return (int) value;
    }

    /** La moyenne rapide doit etre calculee sur moins de seances que la lente (MACD, croisement de moyennes). */
    public static void fastBeforeSlow(String name, int fast, int slow) {
        if (fast >= slow) {
            throw new IllegalArgumentException(name + " : la période rapide doit être plus courte que la période lente");
        }
    }

    /** Multiplicateur de Bollinger : strictement positif et au plus {@value #MAX_BAND_K}. Refuse aussi NaN. */
    public static double bandK(String name, double k) {
        // ecriture en "!(...)" : NaN rend toute comparaison fausse, il est donc refuse
        if (!(k > 0 && k <= MAX_BAND_K)) {
            throw new IllegalArgumentException(name + " : le multiplicateur doit être entre 0 et " + (int) MAX_BAND_K);
        }
        return k;
    }
}
