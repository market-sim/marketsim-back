package tn.esprit.marketsim.agent.replay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.TradingPolicy.Contribution;
import tn.esprit.marketsim.agent.engine.TradingPolicy.PolicyDecision;

/** Explications pedagogiques : elles decrivent les facteurs qui ont REELLEMENT pese dans la decision, sans induire en erreur. */
class ReplayHintsTest {

    private static final Contribution UP = new Contribution("momentum", "Momentum 20j", 1.2, 0.9);
    private static final Contribution DOWN = new Contribution("rsi", "RSI 14", -0.7, -0.6);
    private static final Contribution TREND_UP = new Contribution("trend", "Tendance", 0.8, 0.5);

    private static PolicyDecision linear(AgentAction a, Contribution... cs) {
        return new PolicyDecision(a, 0.5, 0.7, List.of(cs), null);
    }

    @Test
    void aBuySignalCitesTheFactorsThatPushToBuy() {
        String why = ReplayHints.why(linear(AgentAction.BUY, UP, DOWN, TREND_UP), 0, 0.08);
        assertTrue(why.startsWith("Le modèle voit un signal d'ACHAT"), why);
        assertTrue(why.contains("le prix monte depuis 20 jours"), why);
        assertTrue(why.contains("tendance courte est haussière"), why);
        assertFalse(why.contains("survente"), "le facteur qui pousse a vendre n'est pas cite comme raison d'acheter : " + why);
    }

    @Test
    void aSellSignalCitesTheFactorsThatPushToSell() {
        Contribution overbought = new Contribution("rsi", "RSI 14", 0.7, -0.6);       // RSI eleve : pousse a vendre
        String why = ReplayHints.why(linear(AgentAction.SELL, UP, overbought), 0, 0.08);
        assertTrue(why.startsWith("Le modèle voit un signal de VENTE"), why);
        assertTrue(why.contains("RSI signale un surachat"), why);
        assertFalse(why.contains("le prix monte"), why);
    }

    @Test
    void anOverboughtRsiBehindABuyIsFlaggedAsARisk() {
        // suivi de tendance : un RSI eleve SOUTIENT l'achat, ce qui contredit la lecture classique : on previent le debutant
        Contribution rsiHigh = new Contribution("rsi", "RSI 14", 0.8, 0.3);
        String why = ReplayHints.why(linear(AgentAction.BUY, UP, rsiHigh), 0, 0.08);
        assertTrue(why.contains("risque de surachat"), why);
        assertFalse(why.contains("signale un surachat"), why);
        // et symetriquement un RSI bas derriere une vente
        Contribution rsiLow = new Contribution("rsi", "RSI 14", -0.8, -0.3);
        String sell = ReplayHints.why(linear(AgentAction.SELL, rsiLow), 0, 0.08);
        assertTrue(sell.contains("un rebond reste possible"), sell);
    }

    @Test
    void aNeutralSignalExplainsTheConflict() {
        String why = ReplayHints.why(linear(AgentAction.HOLD, UP, DOWN), 0, 0.08);
        assertTrue(why.startsWith("Le modèle reste neutre"), why);
        assertTrue(why.contains("pousse à acheter") && why.contains("pousse à vendre"), why);
        assertTrue(ReplayHints.why(linear(AgentAction.HOLD), 0, 0.08).contains("aucun signal assez clair"));
    }

    @Test
    void stopLossAndTakeProfitAreAnnouncedThenFollowedByTheCurrentSignal() {
        String stop = ReplayHints.why(linear(AgentAction.BUY, UP), Backtester.STOP_LOSS, 0.08);
        assertTrue(stop.startsWith("Stop-loss déclenché") && stop.contains("8,0 %"), stop);
        assertTrue(stop.contains("Pour la suite : le modèle voit un signal d'ACHAT"), "le badge et le texte restent coherents : " + stop);
        String tp = ReplayHints.why(linear(AgentAction.SELL, DOWN), Backtester.TAKE_PROFIT, 0.08);
        assertTrue(tp.startsWith("Objectif atteint") && tp.contains("16,0 %"), "objectif = 2 fois le stop : " + tp);
        assertTrue(tp.contains("signal de VENTE"), tp);
    }

    @Test
    void qLearningContributionsAreReadRelativeToTheChosenAction() {
        // Q-learning : une contribution POSITIVE soutient l'action choisie, meme si c'est une vente
        Contribution supportsSell = new Contribution("rsi", "RSI 14", 0.9, 0.4);
        Contribution againstSell = new Contribution("trend", "Tendance", 0.8, -0.3);
        Contribution position = new Contribution("position", "Position actuelle", 1, 0.2);
        PolicyDecision d = new PolicyDecision(AgentAction.SELL, 0.6, 0.6, List.of(supportsSell, againstSell, position), new double[] {0, 0, 1});
        String why = ReplayHints.why(d, 0, 0.08);
        assertTrue(why.contains("VENTE"), why);
        assertTrue(why.contains("RSI signale un surachat"), why);
        assertTrue(why.contains("déjà investi"), "la position fait partie des raisons : " + why);
        assertFalse(why.contains("tendance courte est haussière"), why);
    }
}
