package tn.esprit.marketsim.coach;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.agent.AgentConfigService;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.assistant.AssistantService;
import tn.esprit.marketsim.agent.assistant.AssistantService.ChatResponse;
import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.coach.CoachDtos.CoachAnalysis;
import tn.esprit.marketsim.coach.CoachDtos.CoachChatRequest;
import tn.esprit.marketsim.coach.CoachDtos.Rule;
import tn.esprit.marketsim.ledger.AuditLedger;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.user.User;

/** Coach de trading : analyse de la vraie courbe d'un actif et conversation (voix cote navigateur). */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachService {

    private final MarketDataService marketData;
    private final AgentLabSupport support;
    private final AgentConfigService agentService;
    private final AssistantService assistant;
    private final AuditLedger ledger;

    /**
     * @param agentId agent dont on reprend l'appetit pour le risque (stop, objectif) ; sinon risque neutre 50
     * @param audit   figer le conseil dans le registre d'audit (a faire pour une demande explicite, pas pour le contexte de chat)
     */
    public CoachAnalysis analyze(User user, String symbol, Long agentId, boolean audit) {
        Asset asset = support.asset(symbol);
        PriceSeries series = marketData.series(asset.getSymbol());
        double risk = agentId == null ? 0.5 : agentService.findOne(agentId, user).risk();
        CoachAnalysis analysis = CoachAnalyzer.analyze(series, asset.getName(), risk, CoachTrackRecord.compute(series, risk));
        if (!audit) return analysis;
        return analysis.withAuditRecord(ledger.record("COACH_ADVICE", null, user.getId(), agentId,
                String.format(Locale.FRANCE, "Coach · %s %s @ %s", analysis.action(), analysis.symbol(), CoachAnalyzer.px(analysis.price())),
                auditPayload(analysis)));
    }

    public ChatResponse chat(User user, CoachChatRequest req) {
        CoachAnalysis analysis = null;
        if (req.symbol() != null && !req.symbol().isBlank()) {
            try {
                analysis = analyze(user, req.symbol(), req.agentId(), false);
            } catch (RuntimeException e) {
                log.warn("Coach : analyse de {} impossible pour la conversation ({})", req.symbol(), e.getMessage());
            }
        }
        CoachAnalysis shown = analysis;
        return assistant.chatCoach(user, req.messages(), context(req.page(), shown),
                req.webSearch() == null || req.webSearch(), q -> CoachOffline.answer(q, shown));
    }

    /** Bloc de contexte joint a la question : ecran regarde + chiffres de l'analyse (le coach ne parle que de cela). */
    static String context(String page, CoachAnalysis a) {
        StringBuilder sb = new StringBuilder();
        String screen = page == null ? "" : page.replaceAll("[\\r\\n]+", " ").strip();   // champ libre : une seule ligne courte
        if (screen.length() > 80) screen = screen.substring(0, 80);
        sb.append("Écran affiché : ").append(screen.isEmpty() ? "inconnu" : screen).append(".\n");
        if (a == null) {
            sb.append("Aucune analyse de courbe n'est disponible pour le moment (aucun actif choisi dans le coach).");
            return sb.toString();
        }
        var i = a.indicators();
        var l = a.levels();
        sb.append(String.format(Locale.FRANCE, "Analyse de %s (%s) sur cours réels (%s), dernière clôture le %s :\n", a.name(), a.symbol(), a.source(), a.asOf()));
        sb.append(String.format(Locale.FRANCE, "- Cours %s (%+.1f %% en 1 jour, %+.1f %% en 5 jours, %+.1f %% en 20 jours)\n",
                CoachAnalyzer.px(a.price()), a.change1d() * 100, a.change5d() * 100, a.change20d() * 100));
        sb.append(String.format(Locale.FRANCE, "- Signal : %s, indice de confiance du modèle %.0f %% (ce n'est pas une probabilité)\n",
                CoachAnalyzer.verb(a.action()).toUpperCase(Locale.FRANCE), a.confidence() * 100));
        sb.append(String.format(Locale.FRANCE, "- Tendance de fond : %s ; RSI 14 jours %.0f ; moyennes 20/50/200 jours : %s / %s / %s ; volatilité %.0f %% par an (normale %.0f %%)\n",
                CoachAnalyzer.trendWord(i.trend()), i.rsi14(), CoachAnalyzer.px(i.sma20()), CoachAnalyzer.px(i.sma50()),
                CoachAnalyzer.px(i.sma200()), i.volatilityAnnual() * 100, i.volatilityNormal() * 100));
        sb.append(String.format(Locale.FRANCE, "- Support %s ; résistance %s ; stop %s ; objectif %s\n", CoachAnalyzer.px(l.support()),
                l.resistance() == null ? "aucune proche (plus hauts récents)" : CoachAnalyzer.px(l.resistance()),
                CoachAnalyzer.px(l.stop()), CoachAnalyzer.px(l.target())));
        for (Rule r : a.plan()) sb.append("- ").append(r.title()).append(r.active() ? " (CONSEILLÉ MAINTENANT)" : "").append(" : ").append(r.condition()).append('\n');
        sb.append("- Raisons du signal : ").append(a.reasons().stream().map(r -> r.text()).collect(Collectors.joining(" ; "))).append('\n');
        sb.append("- ").append(a.trackRecord() == null ? "Historique réel de la stratégie : insuffisant pour conclure." : a.trackRecord().text());
        return sb.toString();
    }

    private static Map<String, Object> auditPayload(CoachAnalysis a) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", a.symbol());
        p.put("asOf", a.asOf().toString());
        p.put("dataSource", a.source());
        p.put("dataHash", a.dataHash());
        p.put("price", a.price());
        p.put("action", a.action().name());
        p.put("confidence", a.confidence());
        p.put("score", a.score());
        p.put("riskLevel", a.riskLevel());
        p.put("trend", a.indicators().trend());
        p.put("rsi14", a.indicators().rsi14());
        p.put("support", a.levels().support());
        p.put("resistance", a.levels().resistance());
        p.put("stop", a.levels().stop());
        p.put("target", a.levels().target());
        p.put("headline", a.headline());
        if (a.trackRecord() != null) {
            p.put("trackRecordWindows", a.trackRecord().windows());
            p.put("trackRecordPValue", a.trackRecord().pValue());
        }
        return p;
    }
}
