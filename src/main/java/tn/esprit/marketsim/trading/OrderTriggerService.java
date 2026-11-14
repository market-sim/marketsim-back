package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Moteur de declenchement : execute les LIMIT / STOP en attente et les SL / TP des positions ouvertes.
 * <ul>
 *   <li>Balayage planifie toutes les {@code trading.engine.interval-ms} (2 s par defaut) sur un thread DEDIE :
 *       le planificateur Spring partage n'a qu'un thread, que d'autres modules bloquent par des appels reseau
 *       (ex. NewsTvService) ; un {@code @Scheduled} ne garantirait donc pas le declenchement des stop-loss.</li>
 *   <li>Balayage a la demande d'un utilisateur dans GET /orders et GET /portfolio, pour que les declenchements
 *       soient visibles meme si les prix ont change ailleurs entre deux balayages.</li>
 * </ul>
 * Pre-filtre sans verrou avec {@link OrderTriggerEngine} ; seuls les tickets declenches sont traites, un par un,
 * chacun dans sa propre transaction ({@link TradeOrderService#processTrigger}), qui reverifie tout sous verrou
 * (idempotent : un ticket deja traite est ignore).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTriggerService {

    private final TradeOrderRepository repository;
    private final TradeOrderService orders;
    private final PriceProvider prices;
    private final TradingProperties props;

    private ScheduledExecutorService executor;

    @PostConstruct
    void start() {
        if (!props.getEngine().isEnabled()) {
            log.info("Moteur de trading planifie desactive (trading.engine.enabled=false)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trading-engine");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(200, props.getEngine().getIntervalMs());
        executor.scheduleWithFixedDelay(this::scheduledSweep, props.getEngine().getInitialDelayMs(), interval,
                TimeUnit.MILLISECONDS);
        log.info("Moteur de trading demarre (balayage toutes les {} ms)", interval);
    }

    @PreDestroy
    void stop() {
        if (executor != null) executor.shutdownNow();
    }

    public void scheduledSweep() {
        if (!props.getEngine().isEnabled()) return;
        try {
            sweep(repository.findTriggerCandidates());
        } catch (RuntimeException e) {
            log.warn("Moteur de trading : balayage impossible ({})", e.getMessage());
        }
    }

    /** Balayage limite aux tickets d'un utilisateur. Ne leve jamais d'exception. */
    public int sweepUser(Long userId) {
        try {
            return sweep(repository.findTriggerCandidatesByOwner(userId));
        } catch (RuntimeException e) {
            log.warn("Moteur de trading : balayage de l'utilisateur {} impossible ({})", userId, e.getMessage());
            return 0;
        }
    }

    /** @return nombre de tickets executes / clotures */
    int sweep(List<TradeOrder> candidates) {
        Map<String, BigDecimal> marks = new HashMap<>();
        int done = 0;
        for (TradeOrder o : candidates) {
            BigDecimal price = marks.computeIfAbsent(PortfolioCalculator.key(o.getSymbol()), this::priceOrNull);
            if (OrderTriggerEngine.evaluate(o, price) == OrderTriggerEngine.Action.NONE) continue;
            try {
                OrderTriggerEngine.Action action = orders.processTrigger(o.getId());
                if (action != OrderTriggerEngine.Action.NONE) {
                    done++;
                    log.info("Moteur de trading : ticket #{} {} ({} @ {})", o.getId(), action, o.getSymbol(), price);
                }
            } catch (OptimisticLockingFailureException e) {
                log.debug("Ticket #{} deja traite par une autre transaction", o.getId());
            } catch (RuntimeException e) {
                log.warn("Moteur de trading : ticket #{} non traite ({})", o.getId(), e.getMessage());
            }
        }
        return done;
    }

    private BigDecimal priceOrNull(String symbol) {
        try {
            return prices.currentPrice(symbol);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
