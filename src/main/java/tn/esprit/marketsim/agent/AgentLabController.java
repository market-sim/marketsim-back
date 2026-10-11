package tn.esprit.marketsim.agent;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.decision.DecisionService;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.DecisionDto;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.MonteCarloRequest;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.MonteCarloResult;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.PolicyState;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.RatingRequest;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.TrainingRequest;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.TrainingResult;
import tn.esprit.marketsim.agent.montecarlo.MonteCarloRun;
import tn.esprit.marketsim.agent.dto.TournamentDtos.TournamentRequest;
import tn.esprit.marketsim.agent.dto.TournamentDtos.TournamentResult;
import tn.esprit.marketsim.agent.montecarlo.MonteCarloService;
import tn.esprit.marketsim.agent.replay.ReplayService;
import tn.esprit.marketsim.agent.replay.ReplayService.ReplayRequest;
import tn.esprit.marketsim.agent.replay.ReplayService.ReplayResult;
import tn.esprit.marketsim.agent.tournament.TournamentRun;
import tn.esprit.marketsim.agent.tournament.TournamentService;
import tn.esprit.marketsim.agent.training.QLearningTrainer;
import tn.esprit.marketsim.user.User;

/** Module 6 (partie IA) : decisions expliquees, Monte Carlo, apprentissage par renforcement, tournoi. */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentLabController {

    private final AgentConfigService agentService;
    private final DecisionService decisionService;
    private final MonteCarloService monteCarloService;
    private final QLearningTrainer trainer;
    private final TournamentService tournamentService;
    private final ReplayService replayService;

    // ----- Decisions & XAI -----

    @PostMapping("/{id}/decide")
    public List<DecisionDto> decide(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return decisionService.decide(agentService.findOne(id, user), user);
    }

    @GetMapping("/{id}/decisions")
    public List<DecisionDto> decisions(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return decisionService.history(agentService.findOne(id, user));
    }

    @PatchMapping("/decisions/{decisionId}/rating")
    public DecisionDto rate(@PathVariable Long decisionId, @Valid @RequestBody RatingRequest req,
                            @AuthenticationPrincipal User user) {
        return decisionService.rate(decisionId, req, user);
    }

    // ----- Monte Carlo -----

    @PostMapping("/{id}/montecarlo")
    public MonteCarloResult monteCarlo(@PathVariable Long id, @Valid @RequestBody MonteCarloRequest req,
                                       @AuthenticationPrincipal User user) {
        return monteCarloService.run(agentService.findOne(id, user), req);
    }

    @GetMapping("/{id}/montecarlo/runs")
    public List<MonteCarloRun> monteCarloRuns(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return monteCarloService.history(agentService.findOne(id, user));
    }

    // ----- Apprentissage par renforcement -----

    @PostMapping("/{id}/train")
    public TrainingResult train(@PathVariable Long id, @Valid @RequestBody TrainingRequest req,
                                @AuthenticationPrincipal User user) {
        return trainer.train(agentService.findOne(id, user), req);
    }

    @GetMapping("/{id}/policy")
    public List<PolicyState> policy(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return trainer.policy(agentService.findOne(id, user));
    }

    // ----- Replay (demo humain vs agent) -----

    @PostMapping("/{id}/replay")
    public ReplayResult replay(@PathVariable Long id, @Valid @RequestBody ReplayRequest req,
                               @AuthenticationPrincipal User user) {
        return replayService.replay(agentService.findOne(id, user), req);
    }

    // ----- Tournoi entre agents (inference statistique) -----

    @PostMapping("/tournaments")
    public TournamentResult tournament(@Valid @RequestBody TournamentRequest req, @AuthenticationPrincipal User user) {
        return tournamentService.run(user, req);
    }

    @GetMapping("/tournaments")
    public List<TournamentRun> tournaments(@AuthenticationPrincipal User user) {
        return tournamentService.history(user);
    }
}
