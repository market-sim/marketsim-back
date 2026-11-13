package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Status;
import tn.esprit.marketsim.trading.TradingDtos.CreateOrderRequest;
import tn.esprit.marketsim.trading.TradingDtos.OrderResponse;
import tn.esprit.marketsim.trading.TradingDtos.UpdateOrderRequest;
import tn.esprit.marketsim.user.User;

/**
 * Module 2 : cycle de vie des tickets. L'utilisateur vient toujours du JWT et chaque lecture est filtree par
 * proprietaire (un ordre d'un autre utilisateur → 404). Le prix vient exclusivement de {@link PriceProvider}.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class TradeOrderService {

    private final TradeOrderRepository repository;
    private final PriceProvider prices;

    // ===================== Lecture =====================

    /** Ordres de l'utilisateur, plus recents d'abord, avec filtres optionnels (bornes de dates incluses). */
    @Transactional(readOnly = true)
    public List<OrderResponse> findMine(User user, Status status, String symbol, LocalDate from, LocalDate to) {
        LocalDateTime start = from == null ? null : from.atStartOfDay();
        LocalDateTime end = to == null ? null : to.plusDays(1).atStartOfDay();
        Map<String, BigDecimal> marks = new HashMap<>();
        return repository.findByOwnerIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(o -> status == null || o.getStatus() == status)
                .filter(o -> symbol == null || symbol.isBlank() || o.getSymbol().equalsIgnoreCase(symbol.trim()))
                .filter(o -> start == null || (o.getCreatedAt() != null && !o.getCreatedAt().isBefore(start)))
                .filter(o -> end == null || (o.getCreatedAt() != null && o.getCreatedAt().isBefore(end)))
                .map(o -> OrderResponse.of(o, markOrNull(o, marks)))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse findOneDto(Long id, User user) {
        TradeOrder o = findOne(id, user);
        return OrderResponse.of(o, markOrNull(o, new HashMap<>()));
    }

    TradeOrder findOne(Long id, User user) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotFoundException("Ordre introuvable"));
    }

    // ===================== Creation =====================

    /**
     * Cree un ordre. Le prix courant est lu cote serveur ; le statut initial est fixe ici : MARKET → OPEN,
     * LIMIT / STOP → PENDING.
     */
    public OrderResponse create(CreateOrderRequest req, User user) {
        String symbol = req.symbol().trim();
        BigDecimal market = prices.currentPrice(symbol);          // symbole inconnu → 400
        OrderType type = req.typeOrDefault();
        OrderRules.validateType(type, req.limitPrice());
        BigDecimal quantity = OrderRules.normalizeQuantity(req.quantity());
        BigDecimal limit = Money.price(req.limitPrice());
        BigDecimal reference = type == OrderType.MARKET ? market : limit;
        OrderRules.validateProtection(req.side(), reference, req.stopLoss(), req.takeProfit());

        TradeOrder order = new TradeOrder();
        order.setSymbol(symbol.toUpperCase());
        order.setSide(req.side());
        order.setOrderType(type);
        order.setQuantity(quantity);
        order.setLimitPrice(limit);
        order.setStopLoss(Money.price(req.stopLoss()));
        order.setTakeProfit(Money.price(req.takeProfit()));
        order.setOwner(user);
        order.setPrice(reference);
        if (type == OrderType.MARKET) {
            order.setStatus(Status.OPEN);
            order.setExecutedPrice(market);
            order.setExecutedAt(LocalDateTime.now());
        } else {
            order.setStatus(Status.PENDING);
        }
        TradeOrder saved = repository.save(order);
        return OrderResponse.of(saved, market);
    }

    // ===================== Mise a jour des protections =====================

    /**
     * Mise a jour partielle du SL / TP (seuls champs modifiables). Champ absent = inchange ; effacement explicite
     * via clearStopLoss / clearTakeProfit. Uniquement sur un ticket PENDING ou OPEN (sinon 409).
     */
    public OrderResponse update(Long id, UpdateOrderRequest req, User user) {
        TradeOrder order = findOne(id, user);
        if (order.getStatus() != Status.OPEN && order.getStatus() != Status.PENDING) {
            throw new ConflictException("L'ordre #" + id + " est " + order.getStatus() + " : il n'est plus modifiable");
        }
        if (req.clearSl() && req.stopLoss() != null) {
            throw new IllegalArgumentException("stopLoss et clearStopLoss sont incompatibles");
        }
        if (req.clearTp() && req.takeProfit() != null) {
            throw new IllegalArgumentException("takeProfit et clearTakeProfit sont incompatibles");
        }
        BigDecimal sl = req.clearSl() ? null : req.stopLoss() != null ? Money.price(req.stopLoss()) : order.getStopLoss();
        BigDecimal tp = req.clearTp() ? null : req.takeProfit() != null ? Money.price(req.takeProfit()) : order.getTakeProfit();
        BigDecimal reference = order.getStatus() == Status.PENDING ? order.getLimitPrice() : order.entryPrice();
        OrderRules.validateProtection(order.getSide(), reference, sl, tp);
        order.setStopLoss(sl);
        order.setTakeProfit(tp);
        return OrderResponse.of(order, markOrNull(order, new HashMap<>()));
    }

    // ===================== Annulation =====================

    /** Annule un ordre PENDING. Un ticket OPEN → 409 (« utilisez /close »). Aucune suppression physique. */
    public OrderResponse cancel(Long id, User user) {
        TradeOrder order = findOne(id, user);
        OrderStateMachine.transition(order, Status.CANCELLED);
        return OrderResponse.of(order, null);
    }

    // ===================== Utilitaires =====================

    /** Prix courant pour l'affichage d'un ticket OPEN (null si l'actif a disparu du module 5). */
    BigDecimal markOrNull(TradeOrder o, Map<String, BigDecimal> cache) {
        if (o.getStatus() != Status.OPEN) return null;
        return cache.computeIfAbsent(o.getSymbol().toUpperCase(), s -> {
            try {
                return prices.currentPrice(s);
            } catch (RuntimeException e) {
                return null;
            }
        });
    }
}
