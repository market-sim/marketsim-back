package tn.esprit.marketsim.agent.assistant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentConfigRepository;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.decision.AgentDecisionRepository;
import tn.esprit.marketsim.agent.montecarlo.MonteCarloRun;
import tn.esprit.marketsim.agent.montecarlo.MonteCarloRunRepository;
import tn.esprit.marketsim.agent.tournament.TournamentRun;
import tn.esprit.marketsim.agent.tournament.TournamentRunRepository;
import tn.esprit.marketsim.user.User;

/**
 * Photographie des donnees du module 6 de l'utilisateur (agents, dernieres decisions,
 * simulations, tournois) : utilisee par l'assistant en ligne (texte joint a la question)
 * et par l'assistant local (donnees structurees).
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

    public record McInfo(LocalDateTime at, String symbol, int paths, Double crashPct, double meanReturn,
                         double benchmarkReturn, double var95, double cvar95, double sharpe, double meanDrawdown,
                         double probProfit) {
    }

    public record AgentInfo(String name, AgentStrategy strategy, int riskLevel, String assets, boolean active,
                            boolean trained, Integer episodes, McInfo lastMonteCarlo) {
    }

    public record DecisionInfo(LocalDateTime at, String agent, AgentAction action, String symbol, double confidence,
                               Integer rating, String explanation) {
    }

    public record TournamentInfo(LocalDateTime at, String symbol, int paths, String ranking, String winner,
                                 String conclusion) {
    }

    public record Snapshot(String userName, String level, List<AgentInfo> agents, List<DecisionInfo> decisions,
                           TournamentInfo tournament) {
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(User user) {
        List<AgentInfo> agents = agentRepository.findByOwnerId(user.getId()).stream().map(a -> {
            List<MonteCarloRun> runs = monteCarloRepository.findTop20ByAgentIdOrderByCreatedAtDescIdDesc(a.getId());
            McInfo mc = runs.isEmpty() ? null : toMc(runs.get(0));
            return new AgentInfo(a.getName(), a.getStrategy(), a.getRiskLevel() == null ? 50 : a.getRiskLevel(),
                    a.getAllowedAssets(), a.isActive(), a.isTrained(), a.getTrainedEpisodes(), mc);
        }).toList();

        List<DecisionInfo> decisions = decisionRepository.findTop8ByAgentOwnerIdOrderByCreatedAtDescIdDesc(user.getId())
                .stream()
                .map(d -> new DecisionInfo(d.getCreatedAt(), d.getAgent().getName(), d.getAction(), d.getSymbol(),
                        d.getConfidence(), d.getUserRating(), d.getExplanation()))
                .toList();

        List<TournamentRun> tournaments = tournamentRepository.findTop20ByOwnerIdOrderByCreatedAtDescIdDesc(user.getId());
        TournamentInfo tournament = tournaments.isEmpty() ? null : toTournament(tournaments.get(0));

        return new Snapshot(user.getFirstName(), String.valueOf(user.getLevel()), agents, decisions, tournament);
    }

    /** Version texte, jointe a la question pour l'assistant en ligne (Claude). */
    @Transactional(readOnly = true) // l'appel interne a snapshot() ne passe pas par le proxy
    public String build(User user) {
        Snapshot s = snapshot(user);
        StringBuilder sb = new StringBuilder();
        sb.append("Date du jour : ").append(LocalDate.now().format(DATE)).append('\n');
        sb.append("Utilisateur : ").append(s.userName()).append(" (niveau ").append(s.level()).append(")\n");

        sb.append("\nAgents (").append(s.agents().size()).append(") :\n");
        if (s.agents().isEmpty()) sb.append("- aucun agent créé\n");
        for (AgentInfo a : s.agents()) {
            sb.append(String.format(Locale.FRANCE, "- %s : stratégie %s, risque %d/100, actifs %s, %s%s%n",
                    a.name(), a.strategy(), a.riskLevel(), a.assets(),
                    a.active() ? "actif (ses décisions deviennent des ordres)" : "en pause",
                    a.trained() ? ", entraîné par Q-learning sur " + a.episodes() + " épisodes" : ""));
            McInfo r = a.lastMonteCarlo();
            if (r != null) {
                sb.append(String.format(Locale.FRANCE,
                        "    dernier Monte Carlo (%s, %s, %d trajectoires%s) : rendement moyen %+.1f %% vs buy & hold %+.1f %%, "
                                + "VaR95 %.1f %%, CVaR95 %.1f %%, Sharpe %.2f, drawdown moyen %.1f %%, P(gain) %.0f %%%n",
                        r.at().format(STAMP), r.symbol(), r.paths(),
                        r.crashPct() != null ? ", scénario krach −" + r.crashPct().intValue() + " %" : "",
                        r.meanReturn() * 100, r.benchmarkReturn() * 100, r.var95() * 100, r.cvar95() * 100,
                        r.sharpe(), r.meanDrawdown() * 100, r.probProfit() * 100));
            }
        }

        sb.append("\nDernières décisions des agents :\n");
        if (s.decisions().isEmpty()) sb.append("- aucune décision pour l'instant\n");
        for (DecisionInfo d : s.decisions()) {
            sb.append(String.format(Locale.FRANCE, "- [%s] %s : %s %s (confiance %.0f %%)%s. Explication : %s%n",
                    d.at().format(STAMP), d.agent(), d.action(), d.symbol(), d.confidence() * 100,
                    d.rating() == null ? "" : d.rating() >= 4 ? ", approuvée par l'utilisateur" : ", jugée mauvaise par l'utilisateur",
                    d.explanation()));
        }

        TournamentInfo t = s.tournament();
        if (t != null) {
            sb.append(String.format(Locale.FRANCE, "%nDernier tournoi (%s, %s, %d marchés) : classement %s. %s%n",
                    t.at().format(STAMP), t.symbol(), t.paths(), t.ranking(), t.conclusion()));
        }
        return sb.toString();
    }

    private static McInfo toMc(MonteCarloRun r) {
        return new McInfo(r.getCreatedAt(), r.getSymbol(), r.getPaths(), r.getCrashPct(), r.getMeanReturn(),
                r.getBenchmarkMeanReturn(), r.getVar95(), r.getCvar95(), r.getMeanSharpe(), r.getMeanMaxDrawdown(),
                r.getProbProfit());
    }

    private static TournamentInfo toTournament(TournamentRun t) {
        return new TournamentInfo(t.getCreatedAt(), t.getSymbol(), t.getPaths(), t.getRanking(), t.getWinner(),
                t.getConclusion());
    }
}
