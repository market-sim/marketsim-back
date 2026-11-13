package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.trading.TradeOrder.CloseReason;
import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;
import tn.esprit.marketsim.trading.TradingDtos.CreateOrderRequest;
import tn.esprit.marketsim.trading.TradingDtos.OrderResponse;
import tn.esprit.marketsim.trading.TradingDtos.RiskAssessment;
import tn.esprit.marketsim.trading.TradingDtos.UpdateOrderRequest;
import tn.esprit.marketsim.user.User;

/**
 * Module 2 : moteur d'execution des tickets.
 * <ul>
 *   <li>L'utilisateur vient toujours du JWT ; chaque lecture est filtree par proprietaire (autre utilisateur → 404).</li>
 *   <li>Le prix vient exclusivement de {@link PriceProvider}.</li>
 *   <li>Tout mouvement de solde se fait sous verrou pessimiste du compte ({@link TradingUserRepository#findByIdForUpdate}),
 *       le {@code @Version} du ticket empeche une double execution / cloture.</li>
 *   <li>Regles comptables : voir {@link TradeAccounting}. Statuts : voir {@link OrderStateMachine}.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TradeOrderService {

    private final TradeOrderRepository repository;
    private final TradingUserRepository accounts;
    private final PriceProvider prices;
    private final TradingProperties props;
    private final TradingAudit audit;
    private final PortfolioService portfolio;
    private final RiskService risk;

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
     * Cree et, si possible, execute un ordre. Prix lu cote serveur ; statut fixe ici.
     * <ul>
     *   <li>MARKET : execution immediate au prix courant, ou REJECTED (solde insuffisant).</li>
     *   <li>LIMIT / STOP : PENDING ; si la condition est deja vraie, execution immediate au prix observe.</li>
     * </ul>
     * Un refus metier n'est pas une exception : le ticket REJECTED est enregistre (historique + audit) et le
     * controleur repond 400. Les erreurs de saisie (symbole inconnu, SL/TP incoherents...) levent une
     * IllegalArgumentException (400) sans rien enregistrer.
     */
    public OrderResponse create(CreateOrderRequest req, User user) {
        User account = lockAccount(user.getId());
        TradeOrder order = prepare(account, req.symbol(), req.side(), req.typeOrDefault(),
                OrderRules.normalizeQuantity(req.quantity()), req.limitPrice(), req.stopLoss(), req.takeProfit());
        TradeOrder result = submit(order, account, null);
        return OrderResponse.of(result, markOrNull(result, new HashMap<>()));
    }

    /** Validation et construction du ticket (statut PENDING en memoire, pas encore enregistre). */
    private TradeOrder prepare(User user, String rawSymbol, Side side, OrderType type, BigDecimal quantity,
                               BigDecimal limitPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
        if (rawSymbol == null || rawSymbol.isBlank()) throw new IllegalArgumentException("Symbole obligatoire");
        if (side == null) throw new IllegalArgumentException("Sens (BUY / SELL) obligatoire");
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        BigDecimal market = prices.currentPrice(symbol);          // symbole inconnu → 400
        OrderRules.validateType(type, limitPrice);
        BigDecimal limit = Money.price(limitPrice);
        BigDecimal reference = type == OrderType.MARKET ? market : limit;
        OrderRules.validateProtection(side, reference, stopLoss, takeProfit);

        TradeOrder order = new TradeOrder();
        order.setSymbol(symbol);
        order.setSide(side);
        order.setOrderType(type);
        order.setQuantity(quantity);
        order.setLimitPrice(limit);
        order.setStopLoss(Money.price(stopLoss));
        order.setTakeProfit(Money.price(takeProfit));
        order.setOwner(user);
        order.setPrice(reference);
        order.setFees(Money.zero());
        order.setLegacy(false);
        order.setStatus(Status.PENDING);          // statut initial decide par le serveur
        return order;
    }

    /**
     * Controle de risque (regles dures → REJECTED), puis enregistrement et execution si l'ordre est au marche ou
     * deja declenche. Compte deja verrouille.
     */
    private TradeOrder submit(TradeOrder order, User account, Long agentId) {
        RiskAssessment assessment = risk.assess(account, order.getSymbol(), order.getSide(), order.getQuantity(),
                order.getPrice(), order.getStopLoss() != null);
        if (assessment.blocked()) {
            reject(order, "Risque bloquant (score " + assessment.score() + ") : " + assessment.blockingReasons(), agentId);
            return order;
        }
        TradeOrder saved = repository.save(order);
        audit.record("ORDER_PLACED", saved, agentId, title("Ordre", saved));
        BigDecimal market = prices.currentPrice(saved.getSymbol());
        if (saved.getOrderType() == OrderType.MARKET
                || OrderTriggerEngine.evaluate(saved, market) == OrderTriggerEngine.Action.FILL) {
            fill(saved, account, market, agentId);
        }
        return saved;
    }

    /**
     * Remplit un ticket PENDING au prix observe : debit de principal + frais (LONG comme SHORT, marge 100 %).
     * Solde insuffisant → REJECTED avec la raison (pas de reservation de solde pour les ordres en attente).
     */
    void fill(TradeOrder order, User account, BigDecimal price, Long agentId) {
        BigDecimal cost = TradeAccounting.openCost(order.getQuantity(), price, props.getFeeRate());
        BigDecimal balance = balanceOf(account);
        if (balance.compareTo(cost) < 0) {
            reject(order, String.format(Locale.ROOT, "Solde insuffisant : %s requis (principal + frais), %s disponible",
                    cost.toPlainString(), balance.toPlainString()), agentId);
            return;
        }
        OrderStateMachine.transition(order, Status.OPEN);
        account.setVirtualBalance(Money.amount(balance.subtract(cost)));
        order.setExecutedPrice(price);
        order.setPrice(price);
        order.setExecutedAt(LocalDateTime.now());
        order.setFees(TradeAccounting.fee(order.getQuantity(), price, props.getFeeRate()));
        audit.record("ORDER_FILLED", order, agentId, title("Exécution", order));
        portfolio.recordSnapshot(account, "ORDER_FILLED");
    }

    void reject(TradeOrder order, String reason, Long agentId) {
        OrderStateMachine.transition(order, Status.REJECTED);
        order.setRejectReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
        if (order.getId() == null) repository.save(order);
        audit.record("ORDER_REJECTED", order, agentId, title("Refus", order) + " — " + reason);
    }

    // ===================== Cloture =====================

    /** Cloture manuelle d'un ticket OPEN au prix courant (POST /{id}/close). */
    public OrderResponse close(Long id, User user) {
        User account = lockAccount(user.getId());
        TradeOrder order = findOne(id, user);
        if (order.getStatus() != Status.OPEN) {
            throw new ConflictException("L'ordre #" + id + " est " + order.getStatus() + " : seule une position OPEN peut être clôturée");
        }
        closeTicket(order, account, prices.currentPrice(order.getSymbol()), CloseReason.MANUAL, null);
        return OrderResponse.of(order, null);
    }

    /** Cloture : credit de principal + P&L - frais de cloture (voir {@link TradeAccounting}). */
    void closeTicket(TradeOrder order, User account, BigDecimal price, CloseReason reason, Long agentId) {
        OrderStateMachine.transition(order, Status.CLOSED);
        TradeAccounting.Close c = TradeAccounting.close(order, price, props.getFeeRate());
        account.setVirtualBalance(Money.amount(balanceOf(account).add(c.credit())));
        order.setClosePrice(price);
        order.setClosedAt(LocalDateTime.now());
        order.setCloseReason(reason);
        order.setRealizedPnl(c.pnl());
        order.setFees(Money.orZero(order.getFees()).add(c.closeFee()));
        audit.record("ORDER_CLOSED", order, agentId, title("Clôture " + reason, order) + " P&L " + c.pnl().toPlainString());
        portfolio.recordSnapshot(account, "ORDER_CLOSED");
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
        audit.record("ORDER_CANCELLED", order, null, title("Annulation", order));
        return OrderResponse.of(order, null);
    }

    // ===================== Integration agent (module 6) =====================

    /**
     * Execute une decision de l'agent. BUY → ticket LONG au marche. SELL → s'il existe un ticket LONG OPEN sur ce
     * symbole, le plus ancien est cloture (closeReason = AGENT) ; sinon ouverture d'un SHORT.
     * <p>
     * Ne leve jamais d'exception metier (la transaction de la decision ne doit pas etre annulee) : tout refus donne
     * un ticket REJECTED avec sa raison, et la decision est enregistree avec l'id de ce ticket.
     */
    public TradeOrder placeForAgent(User user, Long agentId, String symbol, Side side, BigDecimal quantity,
                                    BigDecimal stopLoss, BigDecimal takeProfit) {
        User account = lockAccount(user.getId());
        if (side == Side.SELL) {
            Optional<TradeOrder> longTicket = repository.findFirstByOwnerIdAndSymbolIgnoreCaseAndSideAndStatusOrderByIdAsc(
                    user.getId(), symbol, Side.BUY, Status.OPEN);
            if (longTicket.isPresent()) {
                TradeOrder t = longTicket.get();
                closeTicket(t, account, prices.currentPrice(t.getSymbol()), CloseReason.AGENT, agentId);
                return t;
            }
        }
        BigDecimal qty = quantity == null ? BigDecimal.ZERO : Money.price(quantity);
        TradeOrder order;
        try {
            if (qty.signum() <= 0) throw new IllegalArgumentException("Quantité nulle après arrondi");
            order = prepare(account, symbol, side, OrderType.MARKET, qty, null, stopLoss, takeProfit);
        } catch (IllegalArgumentException e) {
            return rejectedForAgent(account, agentId, symbol, side, qty, e.getMessage());
        }
        return submit(order, account, agentId);
    }

    private TradeOrder rejectedForAgent(User user, Long agentId, String symbol, Side side, BigDecimal qty, String reason) {
        TradeOrder o = new TradeOrder();
        o.setSymbol(symbol == null ? "?" : symbol.trim().toUpperCase(Locale.ROOT));
        o.setSide(side == null ? Side.BUY : side);
        o.setOrderType(OrderType.MARKET);
        o.setQuantity(qty.signum() > 0 ? qty : Money.price(BigDecimal.ZERO));
        BigDecimal ref;
        try {
            ref = prices.currentPrice(o.getSymbol());
        } catch (RuntimeException e) {
            ref = Money.price(BigDecimal.ZERO);
        }
        o.setPrice(ref);
        o.setOwner(user);
        o.setFees(Money.zero());
        o.setLegacy(false);
        o.setStatus(Status.PENDING);
        reject(o, "Agent : " + reason, agentId);
        return o;
    }

    // ===================== Moteur de declenchement =====================

    /**
     * Traite un ticket candidat (SL / TP / LIMIT / STOP) dans SA PROPRE transaction : un echec sur un ticket ne
     * bloque pas les autres. Idempotent : apres verrouillage du compte, le ticket est relu et la condition
     * reevaluee ; s'il a deja ete execute / cloture / annule ailleurs, rien ne se passe.
     *
     * @return l'action effectuee
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderTriggerEngine.Action processTrigger(Long orderId) {
        Optional<Long> ownerId = repository.findOwnerIdById(orderId);
        if (ownerId.isEmpty()) return OrderTriggerEngine.Action.NONE;
        User account = lockAccount(ownerId.get());                 // verrou du compte AVANT relecture du ticket
        TradeOrder order = repository.findById(orderId).orElse(null);
        if (order == null) return OrderTriggerEngine.Action.NONE;
        BigDecimal price = prices.currentPrice(order.getSymbol());
        OrderTriggerEngine.Action action = OrderTriggerEngine.evaluate(order, price);
        switch (action) {
            case FILL -> fill(order, account, price, null);
            case STOP_LOSS -> closeTicket(order, account, price, CloseReason.STOP_LOSS, null);
            case TAKE_PROFIT -> closeTicket(order, account, price, CloseReason.TAKE_PROFIT, null);
            default -> { }
        }
        return action;
    }

    // ===================== Utilitaires =====================

    User lockAccount(Long userId) {
        return accounts.findByIdForUpdate(userId).orElseThrow(() -> new NotFoundException("Compte introuvable"));
    }

    BigDecimal balanceOf(User account) {
        return account.getVirtualBalance() == null ? Money.amount(props.getInitialCapital())
                : Money.amount(account.getVirtualBalance());
    }

    /** Prix courant pour l'affichage d'un ticket OPEN (null si l'actif a disparu du module 5). */
    BigDecimal markOrNull(TradeOrder o, Map<String, BigDecimal> cache) {
        if (o.getStatus() != Status.OPEN) return null;
        return cache.computeIfAbsent(o.getSymbol().toUpperCase(Locale.ROOT), s -> {
            try {
                return prices.currentPrice(s);
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    private static String title(String what, TradeOrder o) {
        return String.format(Locale.ROOT, "%s %s %s %s x %s", what, o.getOrderType(), o.getSide(), o.getSymbol(),
                o.getQuantity() == null ? "?" : o.getQuantity().stripTrailingZeros().toPlainString());
    }
}
