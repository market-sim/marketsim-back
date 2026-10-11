package tn.esprit.marketsim.agent.assistant;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentConfigRepository;
import tn.esprit.marketsim.agent.decision.AgentDecision;
import tn.esprit.marketsim.agent.decision.AgentDecisionRepository;
import tn.esprit.marketsim.agent.montecarlo.MonteCarloRun;
import tn.esprit.marketsim.agent.montecarlo.MonteCarloRunRepository;
import tn.esprit.marketsim.agent.tournament.TournamentRun;
import tn.esprit.marketsim.agent.tournament.TournamentRunRepository;
import tn.esprit.marketsim.user.User;

/**
 * Resume texte des donnees du module 6 de l'utilisateur (agents, dernieres decisions,
 * simulations, tournois), joint a chaque question posee a l'assistant.
 */
@Component
@RequiredArgsConstructor
public class AssistantContextBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final AgentConfigRepository agentRepository;
    private final AgentDecisionRepository decisionRepository;
    private final MonteCarloRunRepository monteCarloRepository;
    private final TournamentRunRepository tournamentRepository;

    @Transactional(readOnly = true)
    public String build(User user) {
        StringBuilder sb = new StringBuilder();
        sb.append("Date du jour : ").append(LocalDate.now().format(DATE)).append('\n');
        sb.append("Utilisateur : ").append(user.getFirstName()).append(" (niveau ").append(user.getLevel()).append(")\n");

        List<AgentConfig> agents = agentRepository.findByOwnerId(user.getId());
        sb.append("\nAgents (").append(agents.size()).append(") :\n");
        if (agents.isEmpty()) sb.append("- aucun agent créé\n");
        for (AgentConfig a : agents) {
            sb.append(String.format(Locale.FRANCE, "- %s : stratégie %s, risque %d/100, actifs %s, %s%s%n",
                    a.getName(), a.getStrategy(), a.getRiskLevel(), a.getAllowedAssets(),
                    a.isActive() ? "actif (ses décisions deviennent des ordres)" : "en pause",
                    a.isTrained() ? ", entraîné par Q-learning sur " + a.getTrainedEpisodes() + " épisodes" : ""));
            List<MonteCarloRun> runs = monteCarloRepository.findTop20ByAgentIdOrderByCreatedAtDescIdDesc(a.getId());
            if (!runs.isEmpty()) {
                MonteCarloRun r = runs.get(0);
                sb.append(String.format(Locale.FRANCE,
                        "    dernier Monte Carlo (%s, %s, %d trajectoires%s) : rendement moyen %+.1f %% vs buy & hold %+.1f %%, "
                                + "VaR95 %.1f %%, CVaR95 %.1f %%, Sharpe %.2f, drawdown moyen %.1f %%, P(gain) %.0f %%%n",
                        r.getCreatedAt().format(STAMP), r.getSymbol(), r.getPaths(),
                        r.getCrashPct() != null ? ", scénario krach −" + r.getCrashPct().intValue() + " %" : "",
                        r.getMeanReturn() * 100, r.getBenchmarkMeanReturn() * 100, r.getVar95() * 100,
                        r.getCvar95() * 100, r.getMeanSharpe(), r.getMeanMaxDrawdown() * 100, r.getProbProfit() * 100));
            }
        }

        List<AgentDecision> decisions = decisionRepository.findTop8ByAgentOwnerIdOrderByCreatedAtDescIdDesc(user.getId());
        sb.append("\nDernières décisions des agents :\n");
        if (decisions.isEmpty()) sb.append("- aucune décision pour l'instant\n");
        for (AgentDecision d : decisions) {
            sb.append(String.format(Locale.FRANCE, "- [%s] %s : %s %s (confiance %.0f %%)%s. Explication : %s%n",
                    d.getCreatedAt().format(STAMP), d.getAgent().getName(), d.getAction(), d.getSymbol(),
                    d.getConfidence() * 100,
                    d.getUserRating() == null ? "" : d.getUserRating() >= 4 ? ", approuvée par l'utilisateur" : ", jugée mauvaise par l'utilisateur",
                    d.getExplanation()));
        }

        List<TournamentRun> tournaments = tournamentRepository.findTop20ByOwnerIdOrderByCreatedAtDescIdDesc(user.getId());
        if (!tournaments.isEmpty()) {
            TournamentRun t = tournaments.get(0);
            sb.append(String.format(Locale.FRANCE, "%nDernier tournoi (%s, %s, %d marchés) : classement %s. %s%n",
                    t.getCreatedAt().format(STAMP), t.getSymbol(), t.getPaths(), t.getRanking(), t.getConclusion()));
        }
        return sb.toString();
    }
}
