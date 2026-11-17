package tn.esprit.marketsim.agent.demo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentConfigService;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.anomaly.AgentBehaviorMonitor;
import tn.esprit.marketsim.agent.anomaly.AgentBehaviorMonitor.AnomalyReport;
import tn.esprit.marketsim.agent.anomaly.AnomalyService;
import tn.esprit.marketsim.agent.decision.DecisionService;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.DecisionDto;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.MonteCarloRequest;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.MonteCarloResult;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.RatingRequest;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.TrainingRequest;
import tn.esprit.marketsim.agent.dto.TournamentDtos.TournamentRequest;
import tn.esprit.marketsim.agent.dto.TournamentDtos.TournamentResult;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.montecarlo.MonteCarloService;
import tn.esprit.marketsim.agent.tournament.TournamentService;
import tn.esprit.marketsim.agent.training.QLearningTrainer;
import tn.esprit.marketsim.user.User;

/**
 * Donnees de demonstration (soutenance) : remplit le compte de l'utilisateur connecte avec 4 agents
 * qui illustrent toutes les fonctionnalites du module 6, en passant par les vrais services
 * (aucune donnee inventee : decisions, entrainement, simulations et tournoi sont reellement calcules).
 */
@Service
@RequiredArgsConstructor
public class DemoDataService {

    private final AgentConfigService agentService;
    private final DecisionService decisionService;
    private final QLearningTrainer trainer;
    private final MonteCarloService monteCarloService;
    private final TournamentService tournamentService;
    private final AnomalyService anomalyService;

    public record DemoResult(List<Long> agentIds, Long surveillanceAgentId, Long rlAgentId, AnomalyReport surveillance,
                             MonteCarloResult rlMonteCarlo, TournamentResult tournament) {
    }

    public DemoResult generate(User user) {
        List<Long> ids = new ArrayList<>();

        // 1. Agent de suivi de tendance, decisions notees par l'utilisateur
        AgentConfig momentum = agentService.create(agent("Alpha Momentum", AgentStrategy.MOMENTUM, 50, "AAPL,BTC/USD,GOLD", 10_000), user);
        ids.add(momentum.getId());
        List<DecisionDto> decisions = decisionService.decide(momentum, user, 5);
        for (int i = 0; i < Math.min(6, decisions.size()); i++) {
            decisionService.rate(decisions.get(i).id(), new RatingRequest(i == 4 ? 1 : 5, null), user);
        }

        // 2. Agent prudent de retour a la moyenne
        AgentConfig contrarian = agentService.create(agent("Prudent Contrarian", AgentStrategy.MEAN_REVERSION, 20, "AAPL,GOLD", 5_000), user);
        ids.add(contrarian.getId());
        decisionService.decide(contrarian, user, 4);

        // 3. Agent Q-learning entraine, teste face a un krach
        AgentConfig rl = agentService.create(agent("RL Agent", AgentStrategy.Q_LEARNING, 75, "AAPL", 10_000), user);
        ids.add(rl.getId());
        trainer.train(rl, new TrainingRequest("AAPL", 2000, PriceModel.REGIME_SWITCHING, 3L));
        decisionService.decide(rl, user, 3);
        monteCarloService.run(rl, new MonteCarloRequest("AAPL", 2000, 252, PriceModel.REGIME_SWITCHING, 42L, true,
                null, null, null, null, null, null, null));
        MonteCarloResult crash = monteCarloService.run(rl, new MonteCarloRequest("AAPL", 2000, 252, PriceModel.REGIME_SWITCHING,
                42L, true, null, null, null, 20.0, null, 2.0, 20));

        // 4. Demo de surveillance : comportement normal, puis l'agent devient brusquement agressif
        AgentConfig watched = agentService.create(agent("Demo Surveillance", AgentStrategy.MOMENTUM, 40, "AAPL,BTC/USD,GOLD", 10_000), user);
        ids.add(watched.getId());
        decisionService.decide(watched, user, 8);
        AgentConfig aggressive = agentService.update(watched.getId(),
                agent("Demo Surveillance", AgentStrategy.MOMENTUM, 95, "AAPL,BTC/USD,GOLD", 20_000), user);
        decisionService.decide(aggressive, user, 4);
        AnomalyReport report = anomalyService.report(aggressive);
        // decisions aleatoires : quelques analyses de plus garantissent l'alerte pour la demonstration
        for (int extra = 0; extra < 3 && report.status() != AgentBehaviorMonitor.Status.ALERT; extra++) {
            decisionService.decide(aggressive, user, 2);
            report = anomalyService.report(aggressive);
        }

        // 5. Tournoi entre les trois premiers agents (+ buy & hold)
        TournamentResult tournament = tournamentService.run(user, new TournamentRequest(
                List.of(momentum.getId(), contrarian.getId(), rl.getId()), "AAPL", 2000, 252, PriceModel.REGIME_SWITCHING, 7L, true));

        return new DemoResult(ids, watched.getId(), rl.getId(), report, crash, tournament);
    }

    private static AgentConfig agent(String name, AgentStrategy strategy, int risk, String assets, int maxPosition) {
        AgentConfig a = new AgentConfig();
        a.setName(name);
        a.setStrategy(strategy);
        a.setRiskLevel(risk);
        a.setAllowedAssets(assets);
        a.setMaxPositionSize(BigDecimal.valueOf(maxPosition));
        return a;
    }
}
