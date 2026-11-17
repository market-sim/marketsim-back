package tn.esprit.marketsim.agent.replay;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.TradingPolicy.Contribution;
import tn.esprit.marketsim.agent.engine.TradingPolicy.PolicyDecision;
import tn.esprit.marketsim.coach.CoachAnalyzer;

/**
 * Phrase pedagogique de chaque journee du replay : le signal du modele et pourquoi, a partir des contributions
 * (valeurs de Shapley exactes pour un modele lineaire, attribution par occlusion pour le Q-learning) de la decision
 * que l'agent a REELLEMENT prise ce jour-la. Rien n'est invente : la phrase decrit les facteurs qui ont pese.
 *
 * <p>La phrase decrit le SIGNAL (achat, vente, neutre), pas l'ordre a passer : le conseil adapte a la position du joueur
 * (garder, attendre, rester a l'ecart...) est affiche a cote par l'interface.
 */
public final class ReplayHints {

    private ReplayHints() {
    }

    /**
     * @param event    evenement du jour (stop-loss, take-profit...) : il est annonce avant le signal
     * @param stopPct  distance du stop-loss de l'agent (fraction du prix d'entree)
     */
    public static String why(PolicyDecision d, int event, double stopPct) {
        String signal = signal(d);
        if (event == Backtester.STOP_LOSS) {
            return String.format(Locale.FRANCE, "Stop-loss déclenché : le prix est tombé de %.1f %% sous l'entrée, la perte est coupée pour ne pas s'aggraver. Pour la suite : ", stopPct * 100)
                    + lowerFirst(signal);
        }
        if (event == Backtester.TAKE_PROFIT) {
            return String.format(Locale.FRANCE, "Objectif atteint (+%.1f %%) : le gain est encaissé, c'est le take-profit. Pour la suite : ", 2 * stopPct * 100)
                    + lowerFirst(signal);
        }
        return signal;
    }

    private static String signal(PolicyDecision d) {
        // Q-learning : une contribution positive soutient l'action choisie ; modele lineaire : positive = pousse a acheter
        boolean relativeToAction = d.qValues() != null;
        AgentAction action = d.action();
        List<Contribution> support = d.contributions().stream()
                .filter(c -> Math.abs(c.contribution()) > 0.02 && (relativeToAction || action != AgentAction.SELL ? c.contribution() > 0 : c.contribution() < 0))
                .sorted(Comparator.comparingDouble(c -> -Math.abs(c.contribution()))).limit(2).toList();

        switch (action) {
            case BUY:
                return "Le modèle voit un signal d'ACHAT : " + reasons(support, action, "le score global du modèle est positif") + ".";
            case SELL:
                return "Le modèle voit un signal de VENTE : " + reasons(support, action, "le score global du modèle est négatif") + ".";
            default:
                if (relativeToAction) {
                    return "Le modèle reste neutre : " + reasons(support, action, "aucune action n'est clairement meilleure") + ".";
                }
                Contribution up = d.contributions().stream().filter(c -> c.contribution() > 0.02)
                        .max(Comparator.comparingDouble(Contribution::contribution)).orElse(null);
                Contribution down = d.contributions().stream().filter(c -> c.contribution() < -0.02)
                        .min(Comparator.comparingDouble(Contribution::contribution)).orElse(null);
                if (up != null && down != null) {
                    return "Le modèle reste neutre : " + describe(up, AgentAction.BUY) + " (ça pousse à acheter), mais " + describe(down, AgentAction.SELL)
                            + " (ça pousse à vendre) : les signaux se compensent.";
                }
                return "Le modèle reste neutre : aucun signal assez clair, mieux vaut ne rien faire.";
        }
    }

    private static String reasons(List<Contribution> factors, AgentAction action, String fallback) {
        return factors.isEmpty() ? fallback : factors.stream().map(c -> describe(c, action)).collect(Collectors.joining(" et "));
    }

    /**
     * Un RSI eleve qui soutient un ACHAT (ou un RSI bas qui soutient une VENTE) est la logique de suivi de tendance du modele,
     * mais va a l'inverse de la lecture classique (surachat = prudence) : on le dit, pour ne pas induire un debutant en erreur.
     */
    static String describe(Contribution c, AgentAction action) {
        if (c.feature().equals("position")) {
            return c.value() >= 1 ? "tu es déjà investi" : "tu n'as pas encore de position";
        }
        if (c.feature().equals("rsi")) {
            if (action == AgentAction.BUY && c.value() > 0.3) {
                return "le RSI est élevé : le modèle y voit de la force (mais attention, c'est aussi un risque de surachat)";
            }
            if (action == AgentAction.SELL && c.value() < -0.3) {
                return "le RSI est bas : le modèle y voit de la faiblesse (mais un rebond reste possible)";
            }
        }
        return CoachAnalyzer.describe(c.feature(), c.value());
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
