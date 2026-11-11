package tn.esprit.marketsim.user.level;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import tn.esprit.marketsim.trading.TradeOrder;
import tn.esprit.marketsim.trading.TradeOrderRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogRepository;
import tn.esprit.marketsim.user.activity.ActivityOutcome;

@Component
public class UserLevelFeatureExtractor {

    private final ActivityLogRepository activityLogRepository;
    private final TradeOrderRepository tradeOrderRepository;

    public UserLevelFeatureExtractor(ActivityLogRepository activityLogRepository,
                                     TradeOrderRepository tradeOrderRepository) {
        this.activityLogRepository = activityLogRepository;
        this.tradeOrderRepository = tradeOrderRepository;
    }

    public UserLevelFeatures extract(Long userId, LocalDateTime createdAt, Instant asOf) {
        long logins = activityLogRepository.countByActorIdAndActionAndOutcomeAndOccurredAtLessThanEqual(
                userId, ActivityAction.LOGIN, ActivityOutcome.SUCCESS, asOf);
        long profileUpdates = activityLogRepository.countByActorIdAndActionAndOutcomeAndOccurredAtLessThanEqual(
                userId, ActivityAction.PROFILE_UPDATE, ActivityOutcome.SUCCESS, asOf);

        LocalDateTime cutoff = LocalDateTime.ofInstant(asOf, ZoneId.systemDefault());
        List<TradeOrder> orders = tradeOrderRepository.findByOwnerIdOrderByCreatedAtDesc(userId).stream()
                .filter(order -> order.getCreatedAt() == null || !order.getCreatedAt().isAfter(cutoff))
                .toList();
        int orderCount = orders.size();
        int closed = 0;
        int protectedOrders = 0;
        int buys = 0;
        Set<String> symbols = new HashSet<>();
        for (TradeOrder order : orders) {
            if (order.getStatus() == TradeOrder.Status.CLOSED) {
                closed++;
            }
            if (order.getStopLoss() != null || order.getTakeProfit() != null) {
                protectedOrders++;
            }
            if (order.getSide() == TradeOrder.Side.BUY) {
                buys++;
            }
            if (order.getSymbol() != null && !order.getSymbol().isBlank()) {
                symbols.add(order.getSymbol());
            }
        }

        double closedRate = orderCount == 0 ? 0 : (double) closed / orderCount;
        double protectionRate = orderCount == 0 ? 0 : (double) protectedOrders / orderCount;
        double sideBalance = orderCount == 0 ? 0 : 1.0 - Math.abs(buys - (orderCount - buys)) / (double) orderCount;
        double ageNorm = 0;
        if (createdAt != null) {
            long days = Math.max(0, ChronoUnit.DAYS.between(createdAt, LocalDateTime.now()));
            ageNorm = Math.min(1, days / 60.0);
        }

        return new UserLevelFeatures(
                Math.min(1, logins / 30.0),
                Math.min(1, profileUpdates / 5.0),
                Math.min(1, orderCount / 20.0),
                closedRate,
                protectionRate,
                Math.min(1, symbols.size() / 5.0),
                sideBalance,
                ageNorm,
                (int) Math.min(logins, Integer.MAX_VALUE));
    }
}
