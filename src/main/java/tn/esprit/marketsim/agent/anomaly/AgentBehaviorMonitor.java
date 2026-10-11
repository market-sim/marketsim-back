package tn.esprit.marketsim.agent.anomaly;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Interface publique du module 6 : surveillance comportementale des agents de trading.
 *
 * <p>Les autres modules (par exemple le Module 1 et sa detection d'anomalies / supervision admin)
 * peuvent l'injecter sans dependre du code interne du module 6 :
 * <pre>
 *   private final AgentBehaviorMonitor agentMonitor;
 *   ...
 *   agentMonitor.reportsFor(userId).stream().filter(r -&gt; r.status() == Status.ALERT)...
 * </pre>
 */
public interface AgentBehaviorMonitor {

    enum Status { NORMAL, WATCH, ALERT, INSUFFICIENT_DATA }

    /** D'ou vient la reference "normale" : historique reel de l'agent, ou profil simule (agent recent). */
    enum BaselineSource { HISTORY, SIMULATED }

    /**
     * @param anomalyScore 0 (rien d'anormal) a 100 (anomalie grave)
     */
    record AnomalyReport(Long agentId, String agentName, Status status, int anomalyScore, BaselineSource baselineSource,
                         int baselineSize, int recentSize, List<AnomalyDetector.Anomaly> anomalies, String summary,
                         LocalDateTime generatedAt) {
    }

    /** Rapports de tous les agents d'un utilisateur. */
    List<AnomalyReport> reportsFor(Long userId);
}
