package tn.esprit.marketsim.trading;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.ledger.AuditLedger;

/**
 * Trace les evenements d'ordre dans le registre d'audit (module 6) : ORDER_PLACED, ORDER_FILLED, ORDER_CLOSED,
 * ORDER_REJECTED, ORDER_CANCELLED.
 * <p>
 * Une panne du registre ne doit JAMAIS bloquer un ordre : l'ecriture a lieu APRES le commit de la transaction de
 * l'ordre, dans sa propre transaction (REQUIRES_NEW), et toute exception est journalisee puis ignoree.
 */
@Slf4j
@Component
public class TradingAudit {

    private final AuditLedger ledger;
    private final TransactionTemplate requiresNew;

    public TradingAudit(AuditLedger ledger, PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String type, TradeOrder o, Long agentId, String title) {
        // Valeurs figees maintenant (l'entite peut encore changer avant le commit)
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", o.getId());
        p.put("symbol", o.getSymbol());
        p.put("side", o.getSide() == null ? null : o.getSide().name());
        p.put("orderType", o.getOrderType() == null ? null : o.getOrderType().name());
        p.put("status", o.getStatus() == null ? null : o.getStatus().name());
        p.put("quantity", str(o.getQuantity()));
        p.put("price", str(o.getPrice()));
        p.put("limitPrice", str(o.getLimitPrice()));
        p.put("executedPrice", str(o.getExecutedPrice()));
        p.put("closePrice", str(o.getClosePrice()));
        p.put("closeReason", o.getCloseReason() == null ? null : o.getCloseReason().name());
        p.put("realizedPnl", str(o.getRealizedPnl()));
        p.put("fees", str(o.getFees()));
        p.put("stopLoss", str(o.getStopLoss()));
        p.put("takeProfit", str(o.getTakeProfit()));
        p.put("rejectReason", o.getRejectReason());
        p.put("source", agentId == null ? "USER" : "AGENT");
        Long refId = o.getId();
        Long ownerId = o.getOwner() == null ? null : o.getOwner().getId();

        Runnable write = () -> {
            try {
                requiresNew.executeWithoutResult(s -> ledger.record(type, refId, ownerId, agentId, title, p));
            } catch (RuntimeException e) {
                log.warn("Audit {} de l'ordre {} impossible : {}", type, refId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }

    private static String str(Object v) {
        return v == null ? null : v instanceof java.math.BigDecimal b ? b.toPlainString() : v.toString();
    }
}
