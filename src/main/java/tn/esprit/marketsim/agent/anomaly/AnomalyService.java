package tn.esprit.marketsim.agent.anomaly;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentConfigRepository;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.anomaly.AnomalyDetector.Anomaly;
import tn.esprit.marketsim.agent.anomaly.AnomalyDetector.Observation;
import tn.esprit.marketsim.agent.anomaly.AnomalyDetector.Severity;
import tn.esprit.marketsim.agent.decision.AgentDecision;
import tn.esprit.marketsim.agent.decision.AgentDecisionRepository;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.MarketFeatures;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.engine.TradingPolicy;
import tn.esprit.marketsim.market.Asset;

/**
 * Surveillance comportementale des agents.
 *
 * <p>Reference "normale" :
 * <ul>
 *   <li>l'historique de l'agent (toutes ses decisions sauf les 8 dernieres) des qu'il en a au moins 15 ;</li>
 *   <li>sinon un profil simule : 60 decisions produites par sa politique actuelle sur des marches
 *       generes exactement comme pour les vraies decisions (agent recent).</li>
 * </ul>
 * Les 8 dernieres decisions sont comparees a cette reference par {@link AnomalyDetector}.
 */
@Service
@RequiredArgsConstructor
public class AnomalyService implements AgentBehaviorMonitor {

    static final int SIMULATED_PROFILE_SIZE = 60;
    static final int HISTORY_DAYS = 120;

    private final AgentConfigRepository agentRepository;
    private final AgentDecisionRepository decisionRepository;
    private final AgentLabSupport support;

    @Override
    @Transactional(readOnly = true)
    public List<AnomalyReport> reportsFor(Long userId) {
        return agentRepository.findByOwnerId(userId).stream().map(this::report).toList();
    }

    @Transactional(readOnly = true)
    public AnomalyReport report(AgentConfig agent) {
        // decisions en ordre chronologique
        List<AgentDecision> history = new ArrayList<>(decisionRepository.findTop200ByAgentIdOrderByCreatedAtDescIdDesc(agent.getId()));
        java.util.Collections.reverse(history);
        List<Observation> all = history.stream().map(AnomalyService::observe).toList();

        if (all.size() < AnomalyDetector.MIN_RECENT) {
            return new AnomalyReport(agent.getId(), agent.getName(), Status.INSUFFICIENT_DATA, 0, null, 0, all.size(),
                    List.of(), "Pas encore assez de décisions à surveiller (" + all.size() + " sur "
                    + AnomalyDetector.MIN_RECENT + " minimum). Lancez quelques analyses du marché.", LocalDateTime.now());
        }

        int recentSize = Math.min(AnomalyDetector.RECENT_WINDOW, all.size());
        List<Observation> recent = all.subList(all.size() - recentSize, all.size());
        List<Observation> past = all.subList(0, all.size() - recentSize);

        BaselineSource source;
        List<Observation> baseline;
        if (past.size() >= AnomalyDetector.MIN_HISTORY_BASELINE) {
            source = BaselineSource.HISTORY;
            baseline = past;
        } else {
            try {
                baseline = simulatedProfile(agent);
                source = BaselineSource.SIMULATED;
            } catch (IllegalArgumentException e) {
                return new AnomalyReport(agent.getId(), agent.getName(), Status.INSUFFICIENT_DATA, 0, null, 0, recentSize,
                        List.of(), e.getMessage(), LocalDateTime.now());
            }
        }

        List<Anomaly> anomalies = AnomalyDetector.detect(baseline, recent);
        Status status = anomalies.stream().anyMatch(a -> a.severity() != Severity.LOW) ? Status.ALERT
                : anomalies.isEmpty() ? Status.NORMAL : Status.WATCH;
        int score = anomalies.stream().mapToInt(a -> switch (a.severity()) {
            case HIGH -> 90;
            case MEDIUM -> 65;
            case LOW -> 35;
        }).max().orElse(5);

        return new AnomalyReport(agent.getId(), agent.getName(), status, score, source, baseline.size(), recentSize,
                anomalies, summary(agent.getName(), status, anomalies, source), LocalDateTime.now());
    }

    private static String summary(String name, Status status, List<Anomaly> anomalies, BaselineSource source) {
        String ref = source == BaselineSource.HISTORY ? "à son historique" : "à son profil de référence simulé";
        return switch (status) {
            case NORMAL -> name + " se comporte normalement par rapport " + ref + ".";
            case WATCH -> name + " présente un signal faible à surveiller : " + anomalies.get(0).title().toLowerCase(Locale.FRANCE) + ".";
            case ALERT -> String.format(Locale.FRANCE, "Alerte : %s se comporte de façon inhabituelle par rapport %s (%s).",
                    name, ref, anomalies.stream().map(a -> a.title().toLowerCase(Locale.FRANCE)).limit(2).reduce((a, b) -> a + ", " + b).orElse(""));
            case INSUFFICIENT_DATA -> "Pas assez de données.";
        };
    }

    private static Observation observe(AgentDecision d) {
        double notional = d.getAction() == AgentAction.HOLD ? 0 : d.getQuantity() * d.getPrice();
        return new Observation(d.getAction(), d.getConfidence(), notional, d.getUserRating());
    }

    /**
     * Profil de reference d'un agent recent : sa politique actuelle decide sur 60 marches generes
     * comme les vrais (historique GBM de 120 jours par actif). Graine fixe par agent : profil stable.
     */
    List<Observation> simulatedProfile(AgentConfig agent) {
        TradingPolicy policy = support.policy(agent);
        List<String> symbols = Arrays.stream(String.valueOf(agent.getAllowedAssets()).split(","))
                .map(String::trim).filter(s -> !s.isEmpty() && !s.equals("null")).toList();
        if (symbols.isEmpty()) throw new IllegalArgumentException("Aucun actif autorisé pour cet agent");

        SplittableRandom rng = new SplittableRandom(agent.getId() * 0x9E3779B97F4A7C15L);
        List<Observation> profile = new ArrayList<>();
        for (int k = 0; k < SIMULATED_PROFILE_SIZE; k++) {
            Asset asset = support.asset(symbols.get(k % symbols.size()));
            AssetDynamics dynamics = support.dynamics(asset);
            double[] path = PathGenerator.generate(1.0, HISTORY_DAYS, dynamics, PriceModel.GBM, rng);
            MarketFeatures f = MarketFeatures.compute(path, HISTORY_DAYS, dynamics.sigma());
            TradingPolicy.PolicyDecision d = policy.decide(f, 0);
            double notional = d.action() == AgentAction.HOLD ? 0
                    : agent.maxPosition() * Backtester.positionFraction(agent.risk()) * d.confidence();
            profile.add(new Observation(d.action(), d.confidence(), notional, null));
        }
        return profile;
    }
}
