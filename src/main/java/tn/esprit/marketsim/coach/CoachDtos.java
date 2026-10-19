package tn.esprit.marketsim.coach;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.assistant.AssistantService.ChatMessage;

/** Reponses et requetes du coach de trading. */
public final class CoachDtos {

    private CoachDtos() {
    }

    /** @param trend HAUSSIERE, BAISSIERE ou MIXTE (prix, moyenne 50 j et moyenne 200 j) */
    public record Indicators(double rsi14, double sma20, double sma50, double sma200, double volatilityAnnual,
                             double volatilityNormal, double momentum20, String trend) {
    }

    /** Niveaux de prix a surveiller ; resistance nulle si le cours est sur ses plus hauts recents. */
    public record Levels(double support, Double resistance, double stop, double target) {
    }

    /** Un facteur du signal (valeur de Shapley exacte du modele lineaire) expliquee en une phrase. */
    public record Reason(String label, double contribution, String text) {
    }

    /**
     * Une regle du plan : quand acheter, quand garder, quand vendre.
     *
     * @param active la regle qui correspond au signal actuel
     * @param level  prix de declenchement, s'il y en a un
     */
    public record Rule(AgentAction action, String title, String condition, Double level, boolean active) {
    }

    /** Ce que cette strategie a REELLEMENT donne hors echantillon sur cet actif (walk-forward) : honnetete avant tout. */
    public record TrackRecord(int windows, String from, String to, double strategyReturn, double benchmarkReturn,
                              double strategySharpe, double benchmarkSharpe, double strategyMaxDrawdown,
                              double benchmarkMaxDrawdown, double meanExcess, double pValue, String text) {
    }

    public record ChartPoint(LocalDate date, double close, double sma20, double sma50) {
    }

    /**
     * Analyse de la courbe REELLE d'un actif. {@code speech} est le texte lu a voix haute par le coach.
     *
     * @param confidence indice du modele (force du signal), pas une probabilite calibree
     */
    public record CoachAnalysis(String symbol, String name, String source, LocalDate asOf, double price,
                                double change1d, double change5d, double change20d, AgentAction action,
                                double confidence, double score, Indicators indicators, Levels levels,
                                List<Reason> reasons, List<Rule> plan, TrackRecord trackRecord, List<ChartPoint> chart,
                                String headline, String speech, String disclaimer, String dataHash, int riskLevel,
                                Long auditRecordId) {
        public CoachAnalysis withAuditRecord(Long id) {
            return new CoachAnalysis(symbol, name, source, asOf, price, change1d, change5d, change20d, action, confidence,
                    score, indicators, levels, reasons, plan, trackRecord, chart, headline, speech, disclaimer, dataHash,
                    riskLevel, id);
        }
    }

    /**
     * @param symbol actif en cours d'analyse (facultatif) ; {@code page} = ecran que l'utilisateur regarde
     */
    public record CoachChatRequest(@NotEmpty List<@Valid ChatMessage> messages, String symbol, String page, Long agentId,
                                   Boolean webSearch) {
    }
}
