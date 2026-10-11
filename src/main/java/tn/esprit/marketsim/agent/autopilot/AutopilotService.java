package tn.esprit.marketsim.agent.autopilot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.anomaly.AgentBehaviorMonitor.AnomalyReport;
import tn.esprit.marketsim.agent.anomaly.AgentBehaviorMonitor.Status;
import tn.esprit.marketsim.agent.anomaly.AnomalyDetector;
import tn.esprit.marketsim.agent.anomaly.AnomalyService;
import tn.esprit.marketsim.agent.autopilot.AutopilotEvent.Type;
import tn.esprit.marketsim.agent.decision.AgentDecision;
import tn.esprit.marketsim.agent.decision.DecisionService;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.MarketFeatures;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PathGenerator.Shocks;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.agent.engine.TradingPolicy;
import tn.esprit.marketsim.agent.engine.TradingPolicy.PolicyDecision;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.market.Asset;

/**
 * Pilote automatique : l'agent trade seul sur un marche simule en continu (un "jour de bourse"
 * par intervalle), avec un portefeuille papier et trois garde-fous :
 * <ol>
 *   <li>perte maximale depuis le debut : tout est liquide et le pilote s'arrete ;</li>
 *   <li>nombre maximal d'ordres d'ouverture par jour (les ventes de protection restent autorisees) ;</li>
 *   <li>coupe-circuit : la surveillance comportementale est consultee toutes les 8 decisions ; une anomalie
 *       forte, ou une alerte confirmee sur deux fenetres consecutives, arrete le pilote (une seule fenetre
 *       ne suffit pas : on evite d'arreter l'agent sur une fausse alerte).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AutopilotService {

    static final PriceModel MODEL = PriceModel.REGIME_SWITCHING;
    static final int PRICE_WINDOW = 150;
    static final int MAX_CURVE_POINTS = 1500;

    private final AutopilotSessionRepository sessionRepository;
    private final AutopilotAssetRepository assetRepository;
    private final AutopilotEventRepository eventRepository;
    private final AgentLabSupport support;
    private final DecisionService decisionService;
    private final AnomalyService anomalyService;

    // =================================================================================== API

    public record StartRequest(@Min(2) @Max(300) Integer intervalSeconds, @Min(1) @Max(100) Integer maxOrdersPerDay,
                               Double maxLossPct, Boolean killSwitch, Double capital) {
    }

    public record PositionView(String symbol, double price, boolean bear, double units, double entryPrice, double value,
                               double pnlPct, Double stopLoss, Double takeProfit, double[] recentPrices) {
    }

    public record AutopilotView(Long sessionId, AutopilotSession.Status status, String stopReason, int intervalSeconds,
                                int maxOrdersPerDay, double maxLossPct, boolean killSwitch, double capital, double equity,
                                double returnPct, double benchmarkReturnPct, double maxDrawdown, int step, int trades,
                                double winRate, int ordersToday, int consecutiveAlerts, LocalDateTime startedAt,
                                LocalDateTime nextRunAt, List<PositionView> positions, double[] equityCurve,
                                double[] benchmarkCurve, List<AutopilotEvent> events) {
    }

    @Transactional(readOnly = true)
    public AutopilotView view(AgentConfig agent) {
        return sessionRepository.findFirstByAgentIdOrderByIdDesc(agent.getId()).map(this::toView).orElse(null);
    }

    @Transactional
    public AutopilotView start(AgentConfig agent, StartRequest req) {
        support.policy(agent); // echoue proprement si l'agent Q-learning n'est pas entraine
        List<String> symbols = symbols(agent);

        for (AutopilotSession old : sessionRepository.findByAgentIdAndStatusIn(agent.getId(),
                List.of(AutopilotSession.Status.RUNNING, AutopilotSession.Status.PAUSED))) {
            liquidate(old, assetRepository.findBySessionIdOrderByIdAsc(old.getId()));
            halt(old, Type.STOPPED, "Remplacée par une nouvelle session.");
        }

        AutopilotSession s = new AutopilotSession();
        s.setAgent(agent);
        s.setStatus(AutopilotSession.Status.RUNNING);
        s.setIntervalSeconds(req.intervalSeconds() == null ? 10 : req.intervalSeconds());
        s.setMaxOrdersPerDay(req.maxOrdersPerDay() == null ? 10 : req.maxOrdersPerDay());
        s.setMaxLossPct(req.maxLossPct() == null ? 10 : Math.max(1, Math.min(90, req.maxLossPct())));
        s.setKillSwitch(req.killSwitch() == null || req.killSwitch());
        double capital = req.capital() == null || req.capital() <= 0 ? 10_000 : req.capital();
        s.setCapital(capital);
        s.setCash(capital);
        s.setPeakEquity(capital);
        s.setEquityCsv("100");
        s.setBenchmarkCsv("100");
        s.setOrdersDay(LocalDate.now());
        s.setNextRunAt(LocalDateTime.now());
        sessionRepository.save(s);

        // marche simule : 150 jours d'historique termines au prix actuel de l'actif
        SplittableRandom rng = new SplittableRandom(ThreadLocalRandom.current().nextLong());
        for (String symbol : symbols) {
            Asset asset = support.asset(symbol);
            AssetDynamics d = support.dynamics(asset);
            Shocks shocks = PathGenerator.drawShocks(PRICE_WINDOW - 1, d, MODEL, rng);
            double[] path = PathGenerator.build(1.0, shocks, d, MODEL, false);
            double scale = asset.getPrice().doubleValue() / path[path.length - 1];
            for (int i = 0; i < path.length; i++) path[i] *= scale;
            AutopilotAsset a = new AutopilotAsset();
            a.setSession(s);
            a.setSymbol(asset.getSymbol());
            a.setPricesCsv(toCsv(path));
            a.setBear(PathGenerator.lastRegimeIsBear(shocks));
            a.setStartPrice(path[path.length - 1]);
            assetRepository.save(a);
        }
        event(s, Type.STARTED, null, null, null, 100.0, null, String.format(Locale.FRANCE,
                "Pilote automatique démarré : capital %,.0f $, un jour de bourse toutes les %d s, perte max %.0f %%, "
                        + "%d ordres par jour au plus, coupe-circuit %s.",
                capital, s.getIntervalSeconds(), s.getMaxLossPct(), s.getMaxOrdersPerDay(), s.isKillSwitch() ? "activé" : "désactivé"));
        return toView(s);
    }

    @Transactional
    public AutopilotView pause(AgentConfig agent) {
        AutopilotSession s = current(agent);
        if (s.getStatus() == AutopilotSession.Status.RUNNING) {
            s.setStatus(AutopilotSession.Status.PAUSED);
            event(s, Type.PAUSED, null, null, null, lastEquity(s), null, "Pilote mis en pause par l'utilisateur (positions conservées).");
        }
        return toView(s);
    }

    @Transactional
    public AutopilotView resume(AgentConfig agent) {
        AutopilotSession s = current(agent);
        if (s.getStatus() == AutopilotSession.Status.PAUSED) {
            s.setStatus(AutopilotSession.Status.RUNNING);
            s.setNextRunAt(LocalDateTime.now());
            event(s, Type.RESUMED, null, null, null, lastEquity(s), null, "Pilote relancé.");
        }
        return toView(s);
    }

    @Transactional
    public AutopilotView stop(AgentConfig agent) {
        AutopilotSession s = current(agent);
        if (s.getStatus() != AutopilotSession.Status.STOPPED) {
            liquidate(s, assetRepository.findBySessionIdOrderByIdAsc(s.getId()));
            halt(s, Type.STOPPED, "Arrêt demandé par l'utilisateur : positions liquidées.");
        }
        return toView(s);
    }

    /** Avance d'un jour a la demande (demonstration), meme si la session est en pause. */
    @Transactional
    public AutopilotView stepNow(AgentConfig agent) {
        AutopilotSession s = current(agent);
        if (s.getStatus() == AutopilotSession.Status.STOPPED) {
            throw new IllegalArgumentException("La session est arrêtée : démarrez-en une nouvelle.");
        }
        advance(s);
        return toView(s);
    }

    /** Resume en francais des sessions d'un utilisateur (pour l'assistant Atlas). */
    @Transactional(readOnly = true)
    public List<String> summaries(Long userId) {
        List<String> out = new ArrayList<>();
        for (AutopilotSession s : sessionRepository.findByAgentOwnerIdAndStatusIn(userId,
                List.of(AutopilotSession.Status.RUNNING, AutopilotSession.Status.PAUSED, AutopilotSession.Status.STOPPED))) {
            if (s.getStatus() == AutopilotSession.Status.STOPPED
                    && (s.getLastRunAt() == null || s.getLastRunAt().isBefore(LocalDateTime.now().minusDays(1)))) {
                continue; // anciennes sessions : sans interet pour le briefing
            }
            double[] eq = parse(s.getEquityCsv());
            double[] bench = parse(s.getBenchmarkCsv());
            StringBuilder sb = new StringBuilder(String.format(Locale.FRANCE,
                    "Pilote automatique de %s : %s, %d jours simulés, portefeuille %+.1f %% contre %+.1f %% pour le buy and hold, %d ordres clôturés.",
                    s.getAgent().getName(),
                    switch (s.getStatus()) {
                        case RUNNING -> "en marche";
                        case PAUSED -> "en pause";
                        case STOPPED -> "arrêté";
                    },
                    s.getStep(), eq[eq.length - 1] - 100, bench[bench.length - 1] - 100, s.getTrades()));
            if (s.getStatus() == AutopilotSession.Status.STOPPED && s.getStopReason() != null) {
                sb.append(" Raison de l'arrêt : ").append(s.getStopReason());
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ============================================================================ Moteur

    /** Sessions a faire avancer maintenant (appele par le planificateur). */
    @Transactional(readOnly = true)
    public List<Long> dueSessionIds() {
        return sessionRepository.findByStatusAndNextRunAtBefore(AutopilotSession.Status.RUNNING, LocalDateTime.now())
                .stream().map(AutopilotSession::getId).toList();
    }

    @Transactional
    public void tick(Long sessionId) {
        AutopilotSession s = sessionRepository.findById(sessionId).orElse(null);
        if (s == null || s.getStatus() != AutopilotSession.Status.RUNNING) return;
        advance(s);
    }

    /** En cas d'erreur inattendue : la session s'arrete proprement (transaction separee). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long sessionId, String reason) {
        sessionRepository.findById(sessionId).ifPresent(s -> halt(s, Type.ERROR, "Arrêt sur erreur : " + reason));
    }

    /** Un jour de bourse simule. */
    void advance(AutopilotSession s) {
        AgentConfig agent = s.getAgent();
        TradingPolicy policy = support.policy(agent);
        List<AutopilotAsset> assets = assetRepository.findBySessionIdOrderByIdAsc(s.getId());
        if (!LocalDate.now().equals(s.getOrdersDay())) {
            s.setOrdersDay(LocalDate.now());
            s.setOrdersToday(0);
        }
        SplittableRandom rng = new SplittableRandom(ThreadLocalRandom.current().nextLong());
        int n = assets.size();
        double equityStart = equity(s, assets);
        boolean limitLogged = s.getOrdersToday() >= s.getMaxOrdersPerDay();

        for (AutopilotAsset a : assets) {
            AssetDynamics d = support.dynamics(support.asset(a.getSymbol()));
            double[] prices = parse(a.getPricesCsv());
            PathGenerator.Step next = PathGenerator.nextStep(prices[prices.length - 1], a.isBear(), d, MODEL, rng);
            prices = append(prices, next.price());
            a.setBear(next.bear());
            a.setPricesCsv(toCsv(prices));
            double price = next.price();

            // 1. Stop-loss / take-profit
            if (a.getUnits() > 0) {
                if (price <= a.getEntryPrice() * (1 - a.getStopPct())) {
                    close(s, a, price, Type.STOP_LOSS, "Stop-loss déclenché sur " + a.getSymbol() + " : la perte est coupée.", null);
                    continue;
                }
                if (price >= a.getEntryPrice() * (1 + 2 * a.getStopPct())) {
                    close(s, a, price, Type.TAKE_PROFIT, "Objectif atteint sur " + a.getSymbol() + " : gain encaissé.", null);
                    continue;
                }
            }

            // 2. Decision de l'agent (enregistree et expliquee comme une decision manuelle)
            MarketFeatures f = MarketFeatures.compute(prices, prices.length - 1, d.sigma());
            PolicyDecision decision = policy.decide(f, a.getUnits() > 0 ? 1 : 0);
            AgentDecision record = decisionService.recordAutopilotDecision(agent, a.getSymbol(), price, decision, f, d);
            s.setDecisionsSinceCheck(s.getDecisionsSinceCheck() + 1);

            if (decision.action() == AgentAction.BUY && a.getUnits() == 0) {
                if (s.getOrdersToday() >= s.getMaxOrdersPerDay()) {
                    if (!limitLogged) {
                        event(s, Type.ORDER_LIMIT, a.getSymbol(), price, null, equity(s, assets) / s.getCapital() * 100, record.getId(),
                                "Limite de " + s.getMaxOrdersPerDay() + " ordres par jour atteinte : plus d'achat aujourd'hui.");
                        limitLogged = true;
                    }
                    continue;
                }
                double notional = Math.min(s.getCash(), equityStart * Backtester.positionFraction(agent.risk()) / n);
                if (notional < 1) continue;
                a.setUnits(notional * (1 - Backtester.COST) / price);
                a.setEntryPrice(price);
                a.setStopPct(Backtester.stopDistance(agent.risk(), d.dailySigma() * (1 + f.volatility())));
                s.setCash(s.getCash() - notional);
                s.setOrdersToday(s.getOrdersToday() + 1);
                event(s, Type.BUY, a.getSymbol(), price, a.getUnits(), equity(s, assets) / s.getCapital() * 100, record.getId(),
                        record.getExplanation());
            } else if (decision.action() == AgentAction.SELL && a.getUnits() > 0) {
                close(s, a, price, Type.SELL, record.getExplanation(), record.getId());
            }
        }

        double equity = equity(s, assets);
        s.setStep(s.getStep() + 1);
        s.setPeakEquity(Math.max(s.getPeakEquity(), equity));
        double benchmark = assets.stream().mapToDouble(a -> last(a) / a.getStartPrice()).average().orElse(1) * 100;
        s.setEquityCsv(appendCsv(s.getEquityCsv(), equity / s.getCapital() * 100));
        s.setBenchmarkCsv(appendCsv(s.getBenchmarkCsv(), benchmark));
        s.setLastRunAt(LocalDateTime.now());
        s.setNextRunAt(LocalDateTime.now().plusSeconds(s.getIntervalSeconds()));

        // 3. Garde-fou : perte maximale
        if (equity / s.getCapital() - 1 <= -s.getMaxLossPct() / 100) {
            liquidate(s, assets);
            halt(s, Type.LOSS_LIMIT, String.format(Locale.FRANCE,
                    "Perte maximale de %.0f %% atteinte (portefeuille %+.1f %%) : positions liquidées, pilote arrêté.",
                    s.getMaxLossPct(), (equity / s.getCapital() - 1) * 100));
            return;
        }

        // 4. Coupe-circuit : surveillance comportementale toutes les 8 decisions
        if (s.isKillSwitch() && s.getDecisionsSinceCheck() >= AnomalyDetector.RECENT_WINDOW) {
            s.setDecisionsSinceCheck(0);
            AnomalyReport report = anomalyService.report(agent);
            if (report.status() == Status.ALERT) {
                s.setConsecutiveAlerts(s.getConsecutiveAlerts() + 1);
                boolean severe = report.anomalies().stream().anyMatch(x -> x.severity() == AnomalyDetector.Severity.HIGH);
                if (severe || s.getConsecutiveAlerts() >= 2) {
                    liquidate(s, assets);
                    halt(s, Type.KILL_SWITCH, "Coupe-circuit : " + report.summary()
                            + (severe ? " Anomalie forte." : " Alerte confirmée sur deux fenêtres consécutives.")
                            + " Positions liquidées, pilote arrêté.");
                } else {
                    event(s, Type.ANOMALY_WARNING, null, null, null, equity / s.getCapital() * 100, null,
                            report.summary() + " Le pilote continue ; une seconde alerte consécutive l'arrêtera.");
                }
            } else {
                s.setConsecutiveAlerts(0);
            }
        }
    }

    // ============================================================================ Outils

    private void close(AutopilotSession s, AutopilotAsset a, double price, Type type, String message, Long decisionId) {
        double proceeds = a.getUnits() * price * (1 - Backtester.COST);
        double pnl = price / a.getEntryPrice() - 1;
        s.setCash(s.getCash() + proceeds);
        s.setTrades(s.getTrades() + 1);
        if (price > a.getEntryPrice()) s.setWins(s.getWins() + 1);
        double qty = a.getUnits();
        a.setUnits(0);
        List<AutopilotAsset> all = assetRepository.findBySessionIdOrderByIdAsc(s.getId());
        event(s, type, a.getSymbol(), price, qty, equity(s, all) / s.getCapital() * 100, decisionId,
                String.format(Locale.FRANCE, "%s Résultat de la position : %+.1f %%.", message == null ? "" : message, pnl * 100).trim());
    }

    private void liquidate(AutopilotSession s, List<AutopilotAsset> assets) {
        for (AutopilotAsset a : assets) {
            if (a.getUnits() > 0) close(s, a, last(a), Type.SELL, "Liquidation de la position " + a.getSymbol() + ".", null);
        }
    }

    private void halt(AutopilotSession s, Type type, String reason) {
        s.setStatus(AutopilotSession.Status.STOPPED);
        s.setStopReason(reason);
        event(s, type, null, null, null, lastEquity(s), null, reason);
    }

    private void event(AutopilotSession s, Type type, String symbol, Double price, Double qty, Double equity, Long decisionId,
                       String message) {
        AutopilotEvent e = new AutopilotEvent();
        e.setSession(s);
        e.setStep(s.getStep());
        e.setType(type);
        e.setSymbol(symbol);
        e.setPrice(price);
        e.setQuantity(qty);
        e.setEquity(equity);
        e.setDecisionId(decisionId);
        e.setMessage(message != null && message.length() > 600 ? message.substring(0, 600) : message);
        eventRepository.save(e);
    }

    private AutopilotSession current(AgentConfig agent) {
        return sessionRepository.findFirstByAgentIdOrderByIdDesc(agent.getId())
                .orElseThrow(() -> new NotFoundException("Aucune session de pilote automatique pour cet agent"));
    }

    private static double equity(AutopilotSession s, List<AutopilotAsset> assets) {
        return s.getCash() + assets.stream().mapToDouble(a -> a.getUnits() * last(a)).sum();
    }

    private static double lastEquity(AutopilotSession s) {
        double[] eq = parse(s.getEquityCsv());
        return eq[eq.length - 1];
    }

    private static double last(AutopilotAsset a) {
        String csv = a.getPricesCsv();
        return Double.parseDouble(csv.substring(csv.lastIndexOf(',') + 1));
    }

    private AutopilotView toView(AutopilotSession s) {
        List<AutopilotAsset> assets = assetRepository.findBySessionIdOrderByIdAsc(s.getId());
        double equity = equity(s, assets);
        double[] eq = parse(s.getEquityCsv());
        double[] bench = parse(s.getBenchmarkCsv());
        List<PositionView> positions = assets.stream().map(a -> {
            double[] p = parse(a.getPricesCsv());
            double price = p[p.length - 1];
            boolean open = a.getUnits() > 0;
            return new PositionView(a.getSymbol(), price, a.isBear(), a.getUnits(), open ? a.getEntryPrice() : 0,
                    a.getUnits() * price, open ? price / a.getEntryPrice() - 1 : 0,
                    open ? a.getEntryPrice() * (1 - a.getStopPct()) : null,
                    open ? a.getEntryPrice() * (1 + 2 * a.getStopPct()) : null,
                    Arrays.copyOfRange(p, Math.max(0, p.length - 60), p.length));
        }).toList();
        return new AutopilotView(s.getId(), s.getStatus(), s.getStopReason(), s.getIntervalSeconds(), s.getMaxOrdersPerDay(),
                s.getMaxLossPct(), s.isKillSwitch(), s.getCapital(), equity, equity / s.getCapital() - 1,
                bench[bench.length - 1] / 100 - 1, Stats.maxDrawdown(eq), s.getStep(), s.getTrades(),
                s.getTrades() == 0 ? 0 : (double) s.getWins() / s.getTrades(), s.getOrdersToday(), s.getConsecutiveAlerts(),
                s.getStartedAt(), s.getStatus() == AutopilotSession.Status.RUNNING ? s.getNextRunAt() : null, positions,
                eq, bench, eventRepository.findTop60BySessionIdOrderByIdDesc(s.getId()));
    }

    private static List<String> symbols(AgentConfig agent) {
        List<String> symbols = Arrays.stream(String.valueOf(agent.getAllowedAssets()).split(","))
                .map(String::trim).filter(x -> !x.isEmpty() && !x.equals("null")).toList();
        if (symbols.isEmpty()) throw new IllegalArgumentException("Aucun actif autorisé pour cet agent");
        return symbols;
    }

    static double[] parse(String csv) {
        return Arrays.stream(csv.split(",")).mapToDouble(Double::parseDouble).toArray();
    }

    static String toCsv(double[] values) {
        return Arrays.stream(values).mapToObj(v -> String.format(Locale.ROOT, "%.6f", v)).collect(Collectors.joining(","));
    }

    private static double[] append(double[] prices, double price) {
        double[] out = Arrays.copyOfRange(prices, Math.max(0, prices.length + 1 - PRICE_WINDOW), prices.length + 1);
        out[out.length - 1] = price;
        return out;
    }

    private static String appendCsv(String csv, double value) {
        String out = csv + "," + String.format(Locale.ROOT, "%.4f", value);
        int count = out.length() - out.replace(",", "").length() + 1;
        if (count > MAX_CURVE_POINTS) out = out.substring(out.indexOf(',') + 1);
        return out;
    }
}
