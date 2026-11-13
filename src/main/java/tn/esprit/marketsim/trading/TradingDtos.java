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

    // ===================== Portefeuille =====================

    /** Position agregee par symbole (voir {@link PortfolioCalculator} pour les definitions). */
    public record PositionDto(String symbol, BigDecimal netQuantity, BigDecimal longQuantity, BigDecimal shortQuantity,
                              BigDecimal avgEntryPrice, BigDecimal marketPrice, BigDecimal marketValue,
                              BigDecimal grossExposure, BigDecimal unrealizedPnl, BigDecimal weightPct, int tickets) {
    }

    public record PortfolioResponse(BigDecimal cash, BigDecimal equity, BigDecimal investedValue,
                                    BigDecimal grossExposure, BigDecimal netExposure, BigDecimal unrealizedPnl,
                                    BigDecimal realizedPnl, BigDecimal totalFees, BigDecimal totalReturnPct,
                                    BigDecimal initialCapital, int openPositions, int pendingOrders,
                                    java.util.List<PositionDto> positions) {
    }

    /** Point de la courbe d'equity. */
    public record EquityPoint(LocalDateTime at, BigDecimal equity, BigDecimal cash, String event) {
    }

    // ===================== Risque =====================

    /** Simulation a blanc d'un ordre (POST /api/trading/risk/assess) : rien n'est cree. */
    public record RiskAssessRequest(
            @NotBlank @Size(max = 32) String symbol,
            @NotNull Side side,
            OrderType orderType,
            @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
            @Positive @Digits(integer = 13, fraction = 6) BigDecimal limitPrice,
            @Positive @Digits(integer = 13, fraction = 6) BigDecimal stopLoss) {
    }

    public enum RiskLevel { LOW, MEDIUM, HIGH, BLOCKED }

    /**
     * Controle individuel. {@code blocking=true} : regle dure (echec → ordre refuse) ; {@code false} : simple
     * avertissement.
     */
    public record RiskCheck(String name, boolean passed, boolean blocking, BigDecimal value, BigDecimal limit,
                            String message) {
    }

    public record RiskMetrics(BigDecimal referencePrice, BigDecimal notional, BigDecimal fee, BigDecimal equity,
                              BigDecimal orderPctOfEquity, BigDecimal concentrationAfterPct,
                              BigDecimal grossExposureAfterPct, BigDecimal cashAfter, BigDecimal cashAfterPct,
                              BigDecimal volatilityAnnualPct, String volatilitySource, BigDecimal var95) {
    }

    /** Composantes normalisees (0-1) du score, pour l'explication. */
    public record RiskComponents(double concentration, double orderSize, double volatility, double grossExposure,
                                 double liquidity) {
    }

    public record RiskAssessment(int score, RiskLevel level, java.util.List<RiskCheck> checks, RiskMetrics metrics,
                                 RiskComponents components) {

        public boolean blocked() {
            return level == RiskLevel.BLOCKED;
        }

        /** Messages des regles dures en echec. */
        public String blockingReasons() {
            return checks.stream().filter(c -> c.blocking() && !c.passed()).map(RiskCheck::message)
                    .collect(java.util.stream.Collectors.joining(" ; "));
        }
    }

    /** Risque du portefeuille (GET /api/trading/risk/portfolio). Montants en devise, horizon 1 jour. */
    public record PortfolioRiskResponse(BigDecimal equity, BigDecimal var95, BigDecimal var99, BigDecimal cvar95,
                                        BigDecimal var95Pct, String varMethod, int observations,
                                        BigDecimal grossExposure, BigDecimal netExposure,
                                        BigDecimal grossExposurePct, BigDecimal netExposurePct,
                                        BigDecimal hhi, BigDecimal diversificationScore,
                                        BigDecimal maxDrawdownPct, BigDecimal currentDrawdownPct,
                                        int positions, String assumptions) {
    }
}
