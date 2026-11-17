package tn.esprit.marketsim.agent.anomaly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.anomaly.AnomalyDetector.Anomaly;
import tn.esprit.marketsim.agent.anomaly.AnomalyDetector.Observation;
import tn.esprit.marketsim.agent.anomaly.AnomalyDetector.Severity;
import tn.esprit.marketsim.agent.anomaly.AnomalyDetector.Type;

class AnomalyDetectorTest {

    /**
     * Comportement "normal" realiste : 50 % HOLD, 30 % BUY, 20 % SELL ; la confiance depend du type d'action
     * comme dans l'application (HOLD ~ 85 %, BUY/SELL ~ 65 %) ; positions ~ 5 000 $.
     */
    static List<Observation> normal(int n, SplittableRandom rng) {
        List<Observation> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double u = rng.nextDouble();
            AgentAction a = u < 0.5 ? AgentAction.HOLD : u < 0.8 ? AgentAction.BUY : AgentAction.SELL;
            double conf = Math.max(0.4, Math.min(0.99, (a == AgentAction.HOLD ? 0.85 : 0.65) + 0.08 * rng.nextGaussian()));
            double notional = a == AgentAction.HOLD ? 0 : 5000 * Math.exp(0.1 * rng.nextGaussian());
            list.add(new Observation(a, conf, notional, null));
        }
        return list;
    }

    @Test
    void fewFalseAlarmsOnNormalBehaviour() {
        SplittableRandom rng = new SplittableRandom(1);
        int alerts = 0;
        int trials = 500;
        for (int t = 0; t < trials; t++) {
            List<Anomaly> found = AnomalyDetector.detect(normal(40, rng), normal(8, rng));
            if (found.stream().anyMatch(a -> a.severity() != Severity.LOW)) alerts++;
        }
        assertTrue(alerts < trials * 0.02, "moins de 2 % de fausses alertes, obtenu " + alerts);
    }

    @Test
    void detectsConfidenceDrop() {
        SplittableRandom rng = new SplittableRandom(2);
        List<Observation> recent = new ArrayList<>();
        for (int i = 0; i < 8; i++) recent.add(new Observation(i % 2 == 0 ? AgentAction.BUY : AgentAction.HOLD, (i % 2 == 0 ? 0.40 : 0.55) + 0.02 * rng.nextGaussian(), i % 2 == 0 ? 5000 : 0, null));
        List<Anomaly> found = AnomalyDetector.detect(normal(40, rng), recent);
        Anomaly drop = found.stream().filter(a -> a.type() == Type.CONFIDENCE_DROP).findFirst().orElseThrow();
        assertEquals(Severity.HIGH, drop.severity());
        assertTrue(drop.recent() < drop.baseline());
    }

    @Test
    void noFalseConfidenceAlarmWhenOnlyTheActionMixChangesSlightly() {
        // 8 decisions recentes "normales" mais avec moins de HOLD que d'habitude : ce n'est PAS une chute de confiance
        SplittableRandom rng = new SplittableRandom(9);
        List<Observation> recent = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            AgentAction a = i < 2 ? AgentAction.HOLD : AgentAction.BUY;
            recent.add(new Observation(a, (a == AgentAction.HOLD ? 0.85 : 0.65) + 0.03 * rng.nextGaussian(), a == AgentAction.HOLD ? 0 : 5000, null));
        }
        assertTrue(AnomalyDetector.detect(normal(40, rng), recent).stream().noneMatch(a -> a.type() == Type.CONFIDENCE_DROP));
    }

    @Test
    void detectsActionMixShift() {
        SplittableRandom rng = new SplittableRandom(3);
        List<Observation> recent = new ArrayList<>();
        for (int i = 0; i < 8; i++) recent.add(new Observation(AgentAction.SELL, 0.7, 5000, null)); // ne fait plus que vendre
        Anomaly mix = AnomalyDetector.detect(normal(40, rng), recent).stream()
                .filter(a -> a.type() == Type.ACTION_MIX).findFirst().orElseThrow();
        assertTrue(mix.pValue() < 0.001);
        assertTrue(mix.message().contains("vente"));
    }

    @Test
    void detectsOversizedPositions() {
        SplittableRandom rng = new SplittableRandom(4);
        List<Observation> recent = new ArrayList<>();
        for (int i = 0; i < 8; i++) recent.add(new Observation(i % 2 == 0 ? AgentAction.BUY : AgentAction.HOLD, 0.7, i % 2 == 0 ? 15000 : 0, null));
        assertTrue(AnomalyDetector.detect(normal(40, rng), recent).stream()
                .anyMatch(a -> a.type() == Type.POSITION_SIZE && a.recent() > a.baseline()));
    }

    @Test
    void detectsHumanApprovalDrop() {
        SplittableRandom rng = new SplittableRandom(5);
        List<Observation> baseline = new ArrayList<>();
        for (Observation o : normal(30, rng)) baseline.add(new Observation(o.action(), o.confidence(), o.notional(), 5)); // toutes approuvees
        List<Observation> recent = new ArrayList<>();
        for (Observation o : normal(8, rng)) recent.add(new Observation(o.action(), o.confidence(), o.notional(), 1)); // toutes rejetees
        assertTrue(AnomalyDetector.detect(baseline, recent).stream().anyMatch(a -> a.type() == Type.APPROVAL_DROP));
    }

    @Test
    void exactMultinomialMatchesHandCalculation() {
        double third = 1.0 / 3;
        // 8 decisions toutes identiques, 3 actions equiprobables : seuls les 3 cas "tout pareil" sont aussi rares
        assertEquals(3 * Math.pow(third, 8), AnomalyDetector.exactMultinomialP(new int[] {0, 0, 8}, new double[] {third, third, third}), 1e-12);
        // repartition attendue : p-value = 1
        assertEquals(1.0, AnomalyDetector.exactMultinomialP(new int[] {4, 2, 2}, new double[] {0.5, 0.25, 0.25}), 1e-9);
    }

    @Test
    void needsEnoughData() {
        SplittableRandom rng = new SplittableRandom(6);
        assertTrue(AnomalyDetector.detect(normal(40, rng), normal(3, rng)).isEmpty());
        assertTrue(AnomalyDetector.detect(normal(5, rng), normal(8, rng)).isEmpty());
    }
}
