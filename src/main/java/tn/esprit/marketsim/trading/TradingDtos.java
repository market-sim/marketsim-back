package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tn.esprit.marketsim.trading.TradeOrder.CloseReason;
import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;

/** DTO du module 2 (records immuables). Le client n'envoie jamais ni prix d'execution, ni statut, ni proprietaire. */
public final class TradingDtos {

    private TradingDtos() {
    }

    // ===================== Ordres =====================

    /**
     * Creation d'un ordre. Un eventuel champ {@code price}, {@code status} ou {@code owner} dans le JSON est ignore
     * (champ inconnu du record).
     *
     * @param orderType  MARKET par defaut
     * @param limitPrice niveau LIMIT / STOP (obligatoire pour ces types, interdit pour MARKET)
     */
    public record CreateOrderRequest(
            @NotBlank @Size(max = 32) String symbol,
            @NotNull Side side,
            OrderType orderType,
            @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
            @Positive @Digits(integer = 13, fraction = 6) BigDecimal limitPrice,
            @Positive @Digits(integer = 13, fraction = 6) BigDecimal stopLoss,
            @Positive @Digits(integer = 13, fraction = 6) BigDecimal takeProfit) {

        public OrderType typeOrDefault() {
            return orderType == null ? OrderType.MARKET : orderType;
        }
    }

    /**
     * Mise a jour PARTIELLE des protections : un champ absent (null) reste inchange. Pour effacer explicitement un
     * niveau : {@code clearStopLoss=true} / {@code clearTakeProfit=true}.
     */
    public record UpdateOrderRequest(
            @Positive @Digits(integer = 13, fraction = 6) BigDecimal stopLoss,
            @Positive @Digits(integer = 13, fraction = 6) BigDecimal takeProfit,
            Boolean clearStopLoss,
            Boolean clearTakeProfit) {

        public boolean clearSl() {
            return Boolean.TRUE.equals(clearStopLoss);
        }

        public boolean clearTp() {
            return Boolean.TRUE.equals(clearTakeProfit);
        }
    }

    /**
     * Ticket renvoye au front. {@code price} = prix de reference (compatibilite) ; {@code currentPrice} et
     * {@code unrealizedPnl} ne sont remplis que pour un ticket OPEN.
     */
    public record OrderResponse(
            Long id, String symbol, Side side, String positionType, OrderType orderType, BigDecimal quantity,
            BigDecimal price, BigDecimal limitPrice, BigDecimal stopLoss, BigDecimal takeProfit, Status status,
            LocalDateTime createdAt, BigDecimal executedPrice, LocalDateTime executedAt, LocalDateTime closedAt,
            BigDecimal closePrice, CloseReason closeReason, BigDecimal realizedPnl, BigDecimal fees,
            String rejectReason, BigDecimal currentPrice, BigDecimal unrealizedPnl) {

        public static OrderResponse of(TradeOrder o, BigDecimal currentPrice) {
            boolean open = o.getStatus() == Status.OPEN;
            BigDecimal mark = open ? currentPrice : null;
            BigDecimal unrealized = open && mark != null
                    ? TradeAccounting.pnl(o.getSide(), o.entryPrice(), mark, o.getQuantity())
                    : null;
            return new OrderResponse(o.getId(), o.getSymbol(), o.getSide(),
                    o.getSide() == Side.BUY ? "LONG" : "SHORT", o.getOrderType(), o.getQuantity(), o.getPrice(),
                    o.getLimitPrice(), o.getStopLoss(), o.getTakeProfit(), o.getStatus(), o.getCreatedAt(),
                    o.getExecutedPrice(), o.getExecutedAt(), o.getClosedAt(), o.getClosePrice(), o.getCloseReason(),
                    o.getRealizedPnl(), o.getFees(), o.getRejectReason(), mark, unrealized);
        }
    }

    /** Reponse 400 d'un ordre refuse : le ticket REJECTED est tout de meme enregistre (historique, audit). */
    public record RejectedOrderResponse(int status, String message, OrderResponse order) {
    }
}
