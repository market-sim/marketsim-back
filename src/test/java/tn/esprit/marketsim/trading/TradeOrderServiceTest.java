package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;
import tn.esprit.marketsim.trading.TradingDtos.CreateOrderRequest;
import tn.esprit.marketsim.trading.TradingDtos.OrderResponse;
import tn.esprit.marketsim.trading.TradingDtos.RiskAssessment;
import tn.esprit.marketsim.trading.TradingDtos.RiskCheck;
import tn.esprit.marketsim.trading.TradingDtos.RiskLevel;
import tn.esprit.marketsim.trading.TradingDtos.UpdateOrderRequest;
import tn.esprit.marketsim.user.User;

/** Service des ordres avec des repositories simules (pas de base de donnees). */
@ExtendWith(MockitoExtension.class)
class TradeOrderServiceTest {

    @Mock
    private TradeOrderRepository repository;
    @Mock
    private PriceProvider prices;
    @Mock
    private TradingUserRepository accounts;
    @Mock
    private TradingAudit audit;
    @Mock
    private PortfolioService portfolio;
    @Mock
    private RiskService risk;

    private TradeOrderService service;
    private User alice;

    @BeforeEach
    void setUp() {
        service = new TradeOrderService(repository, accounts, prices, new TradingProperties(), audit, portfolio, risk);
        alice = user(1L);
        lenient().when(accounts.findByIdForUpdate(1L)).thenReturn(Optional.of(alice));
        lenient().when(risk.assess(any(User.class), anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new RiskAssessment(10, RiskLevel.LOW, List.of(), null, null));
        lenient().when(repository.save(any(TradeOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(prices.currentPrice("AAPL")).thenReturn(new BigDecimal("192.450000"));
    }

    @Test
    void marketOrderUsesServerPriceNeverTheClient() {
        // Le DTO n'a meme pas de champ "price" : un prix envoye dans le JSON est ignore a la deserialisation.
        OrderResponse r = service.create(market(Side.BUY, "10"), alice);
        assertEquals(new BigDecimal("192.450000"), r.executedPrice());
        assertEquals(new BigDecimal("192.450000"), r.price());
        assertEquals(Status.OPEN, r.status());
        assertEquals(TradeOrder.Source.USER, r.source());
    }

    @Test
    void unknownSymbolIsRejectedWith400() {
        when(prices.currentPrice(anyString())).thenThrow(new IllegalArgumentException("Symbole inconnu : XXX"));
        CreateOrderRequest req = new CreateOrderRequest("XXX", Side.BUY, OrderType.MARKET, bd("1"), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.create(req, alice));
        verify(repository, never()).save(any());
    }

    @Test
    void initialStatusIsDecidedByServer() {
        CreateOrderRequest limit = new CreateOrderRequest("AAPL", Side.BUY, OrderType.LIMIT, bd("1"), bd("150"), null, null);
        assertEquals(Status.PENDING, service.create(limit, alice).status());
    }

    @Test
    void inconsistentProtectionIsRejected() {
        CreateOrderRequest bad = new CreateOrderRequest("AAPL", Side.BUY, OrderType.MARKET, bd("1"), null, bd("200"), null);
        assertThrows(IllegalArgumentException.class, () -> service.create(bad, alice));
        // LIMIT : la reference est le prix limite, pas le prix courant
        CreateOrderRequest limit = new CreateOrderRequest("AAPL", Side.BUY, OrderType.LIMIT, bd("1"), bd("150"), bd("160"), null);
        assertThrows(IllegalArgumentException.class, () -> service.create(limit, alice));
    }

    @Test
    void partialUpdateKeepsMissingFields() {
        TradeOrder o = open(10L, alice, "100", "95", "120");
        when(repository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(o));

        service.update(10L, new UpdateOrderRequest(bd("97"), null, null, null), alice);
        assertEquals(bd("97"), o.getStopLoss().stripTrailingZeros());
        assertEquals(bd("120"), o.getTakeProfit(), "take-profit absent de la requete : inchange");

        service.update(10L, new UpdateOrderRequest(null, null, null, null), alice);
        assertEquals(bd("120"), o.getTakeProfit());

        service.update(10L, new UpdateOrderRequest(null, null, false, true), alice);
        assertNull(o.getTakeProfit(), "effacement explicite");
        assertEquals(bd("97"), o.getStopLoss().stripTrailingZeros());
    }

    @Test
    void closedOrderCannotBeEdited() {
        TradeOrder o = open(11L, alice, "100", null, null);
        o.setStatus(Status.CLOSED);
        when(repository.findByIdAndOwnerId(11L, 1L)).thenReturn(Optional.of(o));
        assertThrows(ConflictException.class,
                () -> service.update(11L, new UpdateOrderRequest(bd("90"), null, null, null), alice));
    }

    @Test
    void anotherUsersOrderIsNotFound() {
        User bob = user(2L);
        when(repository.findByIdAndOwnerId(10L, 2L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.findOneDto(10L, bob));
        assertThrows(NotFoundException.class, () -> service.cancel(10L, bob));
        assertThrows(NotFoundException.class,
                () -> service.update(10L, new UpdateOrderRequest(bd("1"), null, null, null), bob));
    }

    @Test
    void cancelNeverDeletesAndRefusesOpenPositions() {
        TradeOrder pending = open(12L, alice, "100", null, null);
        pending.setStatus(Status.PENDING);
        pending.setLimitPrice(bd("100"));
        when(repository.findByIdAndOwnerId(12L, 1L)).thenReturn(Optional.of(pending));
        assertEquals(Status.CANCELLED, service.cancel(12L, alice).status());

        TradeOrder open = open(13L, alice, "100", null, null);
        when(repository.findByIdAndOwnerId(13L, 1L)).thenReturn(Optional.of(open));
        assertThrows(ConflictException.class, () -> service.cancel(13L, alice));
        verify(repository, never()).delete(any());
    }

    @Test
    void longRoundTripBalanceChangeEqualsPnlMinusFees() {
        BigDecimal before = alice.getVirtualBalance();
        OrderResponse opened = service.create(market(Side.BUY, "10"), alice);
        // debit = 1924.50 + frais 1.92 (0,1 %)
        assertEquals(new BigDecimal("98073.58"), alice.getVirtualBalance());

        TradeOrder ticket = captured(opened);
        when(prices.currentPrice("AAPL")).thenReturn(new BigDecimal("200.000000"));
        OrderResponse closed = service.close(ticket.getId(), alice);

        assertEquals(Status.CLOSED, closed.status());
        assertEquals(new BigDecimal("75.50"), closed.realizedPnl());                  // (200 - 192.45) x 10
        assertEquals(new BigDecimal("3.92"), closed.fees());                         // 1.92 + 2.00
        assertEquals(closed.realizedPnl().subtract(closed.fees()), alice.getVirtualBalance().subtract(before));
        assertEquals(TradeOrder.CloseReason.MANUAL, closed.closeReason());
    }

    @Test
    void shortRoundTripBalanceChangeEqualsPnlMinusFees() {
        BigDecimal before = alice.getVirtualBalance();
        OrderResponse opened = service.create(market(Side.SELL, "10"), alice);
        assertEquals("SHORT", opened.positionType());
        assertEquals(new BigDecimal("98073.58"), alice.getVirtualBalance(), "marge 100 % + frais bloques");

        TradeOrder ticket = captured(opened);
        when(prices.currentPrice("AAPL")).thenReturn(new BigDecimal("180.000000"));
        OrderResponse closed = service.close(ticket.getId(), alice);

        assertEquals(new BigDecimal("124.50"), closed.realizedPnl());                 // (192.45 - 180) x 10
        assertEquals(closed.realizedPnl().subtract(closed.fees()), alice.getVirtualBalance().subtract(before));
    }

    @Test
    void insufficientBalanceGivesRejectedTicketWithReason() {
        alice.setVirtualBalance(new BigDecimal("1000"));
        OrderResponse r = service.create(market(Side.BUY, "10"), alice);
        assertEquals(Status.REJECTED, r.status());
        assertTrue(r.rejectReason().startsWith("Solde insuffisant"));
        assertEquals(new BigDecimal("1000"), alice.getVirtualBalance(), "solde intact");
        verify(audit).record(org.mockito.ArgumentMatchers.eq("ORDER_REJECTED"), any(), any(), anyString());
    }

    @Test
    void limitAlreadyTriggeredIsFilledImmediatelyAtObservedPrice() {
        CreateOrderRequest limit = new CreateOrderRequest("AAPL", Side.BUY, OrderType.LIMIT, bd("1"), bd("195"), null, null);
        OrderResponse r = service.create(limit, alice);
        assertEquals(Status.OPEN, r.status());
        assertEquals(new BigDecimal("192.450000"), r.executedPrice(), "prix observe, pas le niveau limite");
    }

    @Test
    void limitNotTriggeredStaysPendingWithoutReservingBalance() {
        CreateOrderRequest limit = new CreateOrderRequest("AAPL", Side.BUY, OrderType.LIMIT, bd("1"), bd("150"), null, null);
        assertEquals(Status.PENDING, service.create(limit, alice).status());
        assertEquals(new BigDecimal("100000"), alice.getVirtualBalance());
    }

    @Test
    void closingTwiceIsAConflict() {
        TradeOrder o = open(20L, alice, "100", null, null);
        when(repository.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(o));
        service.close(20L, alice);
        assertThrows(ConflictException.class, () -> service.close(20L, alice));
    }

    @Test
    void agentSellClosesOldestLongInsteadOfOpeningShort() {
        TradeOrder longTicket = open(30L, alice, "190", null, null);
        when(repository.findFirstByOwnerIdAndSymbolIgnoreCaseAndSideAndStatusOrderByIdAsc(1L, "AAPL", Side.BUY, Status.OPEN))
                .thenReturn(Optional.of(longTicket));
        TradeOrder r = service.placeForAgent(alice, 5L, "AAPL", Side.SELL, bd("10"), null, null);
        assertEquals(30L, r.getId());
        assertEquals(Status.CLOSED, r.getStatus());
        assertEquals(TradeOrder.CloseReason.AGENT, r.getCloseReason());
    }

    @Test
    void agentInvalidOrderBecomesRejectedTicketNotException() {
        TradeOrder r = service.placeForAgent(alice, 5L, "AAPL", Side.BUY, bd("1"), bd("500"), null);
        assertEquals(Status.REJECTED, r.getStatus());
        assertTrue(r.getRejectReason().startsWith("Agent"));
        assertEquals(TradeOrder.Source.AGENT, r.getSource());
        assertEquals(TradeOrder.Source.AGENT, OrderResponse.of(r, null).source());
    }

    @Test
    void legacyTicketOnlyCreditsPnlOnClose() {
        TradeOrder o = open(40L, alice, "100", null, null);
        o.setLegacy(true);
        when(repository.findByIdAndOwnerId(40L, 1L)).thenReturn(Optional.of(o));
        when(prices.currentPrice("AAPL")).thenReturn(new BigDecimal("110"));
        service.close(40L, alice);
        // P&L 100 - frais de cloture 1.10 ; le principal (jamais debite) n'est pas restitue
        assertEquals(new BigDecimal("100098.90"), alice.getVirtualBalance());
    }

    /** Le service enregistre le ticket via save() : on le rend retrouvable par id pour la cloture. */
    private TradeOrder captured(OrderResponse r) {
        org.mockito.ArgumentCaptor<TradeOrder> c = org.mockito.ArgumentCaptor.forClass(TradeOrder.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(c.capture());
        TradeOrder t = c.getValue();
        t.setId(99L);
        when(repository.findByIdAndOwnerId(99L, 1L)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void blockedRiskRejectsOrderBeforeAnyBalanceMovement() {
        RiskCheck failed = new RiskCheck("MAX_POSITION_SIZE", false, true, bd("38.49"), bd("25"),
                "Taille de l'ordre 38,49 % de l'equity (max 25 %)");
        when(risk.assess(any(User.class), anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new RiskAssessment(80, RiskLevel.BLOCKED, List.of(failed), null, null));
        OrderResponse r = service.create(market(Side.BUY, "200"), alice);
        assertEquals(Status.REJECTED, r.status());
        assertTrue(r.rejectReason().contains("Risque bloquant"));
        assertEquals(new BigDecimal("100000"), alice.getVirtualBalance());
        verify(repository).save(any(TradeOrder.class));              // ticket REJECTED conserve pour l'historique
    }

    // ----- utilitaires -----

    static User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setVirtualBalance(new BigDecimal("100000"));
        return u;
    }

    static TradeOrder open(Long id, User owner, String entry, String sl, String tp) {
        TradeOrder o = new TradeOrder();
        o.setId(id);
        o.setOwner(owner);
        o.setSymbol("AAPL");
        o.setSide(Side.BUY);
        o.setOrderType(OrderType.MARKET);
        o.setQuantity(bd("10"));
        o.setPrice(bd(entry));
        o.setExecutedPrice(bd(entry));
        o.setStopLoss(sl == null ? null : bd(sl));
        o.setTakeProfit(tp == null ? null : bd(tp));
        o.setStatus(Status.OPEN);
        o.setLegacy(false);
        return o;
    }

    static CreateOrderRequest market(Side side, String qty) {
        return new CreateOrderRequest("AAPL", side, OrderType.MARKET, bd(qty), null, null, null);
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
