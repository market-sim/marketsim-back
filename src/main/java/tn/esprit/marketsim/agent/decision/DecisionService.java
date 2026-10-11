package tn.esprit.marketsim.agent.decision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.ContributionDto;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.DecisionDto;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.RatingRequest;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.MarketFeatures;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.engine.TradingPolicy;
import tn.esprit.marketsim.agent.engine.TradingPolicy.Contribution;
import tn.esprit.marketsim.agent.engine.TradingPolicy.PolicyDecision;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.trading.TradeOrder;
import tn.esprit.marketsim.trading.TradeOrderRepository;
import tn.esprit.marketsim.user.User;

/** Fait decider l'agent sur chacun de ses actifs, explique la decision et l'execute s'il est actif. */
@Service
@RequiredArgsConstructor
public class DecisionService {

    private static final int HISTORY_DAYS = 120;

    private final AgentDecisionRepository repository;
    private final TradeOrderRepository orderRepository;
    private final AgentLabSupport support;

    @Transactional
    public List<DecisionDto> decide(AgentConfig agent, User user) {
        List<String> symbols = Arrays.stream(String.valueOf(agent.getAllowedAssets()).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals("null"))
                .toList();
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Aucun actif autorisé pour cet agent");
        }
        TradingPolicy policy = support.policy(agent);

        List<DecisionDto> result = new ArrayList<>();
        for (String symbol : symbols) {
            Asset asset = support.asset(symbol);
            result.add(toDto(decideOne(agent, user, asset, policy)));
        }
        return result;
    }

    private AgentDecision decideOne(AgentConfig agent, User user, Asset asset, TradingPolicy policy) {
        AssetDynamics dynamics = support.dynamics(asset);
        double price = asset.getPrice().doubleValue();
        double[] history = recentHistory(asset, dynamics, price);
        MarketFeatures features = MarketFeatures.compute(history, history.length - 1, dynamics.sigma());

        int position = hasOpenPosition(user, asset.getSymbol()) ? 1 : 0;
        PolicyDecision d = policy.decide(features, position);

        AgentDecision decision = new AgentDecision();
        decision.setAgent(agent);
        decision.setSymbol(asset.getSymbol());
        decision.setAction(d.action());
        decision.setPrice(price);
        decision.setConfidence(d.confidence());
        decision.setScore(d.score());
        decision.setContributions(d.contributions().stream()
                .map(c -> new FeatureContribution(c.feature(), c.label(), c.value(), c.contribution()))
                .collect(Collectors.toCollection(ArrayList::new)));
        if (d.qValues() != null) {
            decision.setQValues(Arrays.stream(d.qValues())
                    .mapToObj(v -> String.format(Locale.ROOT, "%.4f", v))
                    .collect(Collectors.joining(",")));
        }

        if (d.action() != AgentAction.HOLD) {
            // Taille de position : capital max x appetit pour le risque x confiance
            double notional = agent.maxPosition()
                    * Backtester.positionFraction(agent.risk()) * d.confidence();
            decision.setQuantity(round(notional / price, price > 100 ? 4 : 2));

            // Stop-loss / take-profit adaptes a la volatilite realisee (ratio gain/risque 1:2)
            double realizedDailySigma = dynamics.dailySigma() * (1 + features.volatility());
            double stop = Backtester.stopDistance(agent.risk(), realizedDailySigma);
            boolean buy = d.action() == AgentAction.BUY;
            decision.setStopLoss(round(price * (buy ? 1 - stop : 1 + stop), 4));
            decision.setTakeProfit(round(price * (buy ? 1 + 2 * stop : 1 - 2 * stop), 4));
        }
        decision.setExplanation(explain(asset.getSymbol(), d));

        if (agent.isActive() && d.action() != AgentAction.HOLD) {
            decision.setOrderId(execute(decision, user).getId());
        }
        return repository.save(decision);
    }

    /**
     * Historique recent simule (le module 5 ne fournit qu'un prix courant) : trajectoire GBM
     * de 120 jours qui se termine exactement au prix actuel. La graine change chaque minute.
     * TODO : remplacer par l'historique reel des cotations quand le module 5 le stockera.
     */
    private double[] recentHistory(Asset asset, AssetDynamics dynamics, double price) {
        long seed = asset.getSymbol().hashCode() * 1_000_003L + System.currentTimeMillis() / 60_000;
        double[] path = PathGenerator.generate(1.0, HISTORY_DAYS, dynamics, PriceModel.GBM, new SplittableRandom(seed));
        double scale = price / path[HISTORY_DAYS];
        for (int i = 0; i < path.length; i++) path[i] *= scale;
        return path;
    }

    private boolean hasOpenPosition(User user, String symbol) {
        return orderRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId()).stream()
                .anyMatch(o -> o.getStatus() == TradeOrder.Status.OPEN
                        && o.getSide() == TradeOrder.Side.BUY
                        && o.getSymbol().equalsIgnoreCase(symbol));
    }

    /** Integration module 2 : la decision devient un vrai ordre dans le portefeuille virtuel. */
    private TradeOrder execute(AgentDecision decision, User user) {
        TradeOrder order = new TradeOrder();
        order.setSymbol(decision.getSymbol());
        order.setSide(decision.getAction() == AgentAction.BUY ? TradeOrder.Side.BUY : TradeOrder.Side.SELL);
        order.setOrderType(TradeOrder.OrderType.MARKET);
        order.setQuantity(BigDecimal.valueOf(decision.getQuantity()));
        order.setPrice(BigDecimal.valueOf(decision.getPrice()));
        order.setStopLoss(BigDecimal.valueOf(decision.getStopLoss()));
        order.setTakeProfit(BigDecimal.valueOf(decision.getTakeProfit()));
        order.setOwner(user);
        return orderRepository.save(order);
    }

    /** Explication en francais a partir des 3 variables les plus influentes. */
    static String explain(String symbol, PolicyDecision d) {
        List<Contribution> top = d.contributions().stream()
                .filter(c -> Math.abs(c.contribution()) > 1e-6)
                .sorted(Comparator.comparingDouble((Contribution c) -> -Math.abs(c.contribution())))
                .limit(3)
                .toList();
        String verb = switch (d.action()) {
            case BUY -> "Achat";
            case SELL -> "Vente";
            case HOLD -> "Attente";
        };
        String header = String.format(Locale.FRANCE, "%s %s (confiance %.0f %%)", verb, symbol, d.confidence() * 100);
        if (top.isEmpty()) {
            return header + " : aucun signal marqué, le marché est neutre.";
        }
        String drivers = top.stream()
                .map(c -> String.format(Locale.FRANCE, "%s %s%.2f", c.label(), c.contribution() >= 0 ? "+" : "", c.contribution()))
                .collect(Collectors.joining(", "));
        String reason = d.action() == AgentAction.HOLD
                ? "les signaux se compensent"
                : "principaux facteurs";
        return header + " — " + reason + " : " + drivers + ".";
    }

    // ----- Historique et evaluation -----

    public List<DecisionDto> history(AgentConfig agent) {
        return repository.findTop50ByAgentIdOrderByCreatedAtDescIdDesc(agent.getId()).stream().map(DecisionService::toDto).toList();
    }

    @Transactional
    public DecisionDto rate(Long decisionId, RatingRequest req, User user) {
        AgentDecision decision = repository.findByIdAndAgentOwnerId(decisionId, user.getId())
                .orElseThrow(() -> new NotFoundException("Décision introuvable"));
        decision.setUserRating(req.rating());
        decision.setUserComment(req.comment());
        return toDto(repository.save(decision));
    }

    static DecisionDto toDto(AgentDecision d) {
        double[] q = d.getQValues() == null ? null
                : Arrays.stream(d.getQValues().split(",")).mapToDouble(Double::parseDouble).toArray();
        return new DecisionDto(d.getId(), d.getSymbol(), d.getAction(), d.getQuantity(), d.getPrice(),
                d.getConfidence(), d.getScore(), d.getStopLoss(), d.getTakeProfit(), d.getExplanation(),
                d.getContributions().stream()
                        .map(c -> new ContributionDto(c.getFeature(), c.getLabel(), c.getFeatureValue(), c.getContribution()))
                        .toList(),
                q, d.getOrderId(), d.getUserRating(), d.getUserComment(), d.getCreatedAt());
    }

    private static double round(double v, int decimals) {
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}
