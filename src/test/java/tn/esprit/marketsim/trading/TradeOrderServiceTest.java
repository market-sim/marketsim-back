package tn.esprit.marketsim.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import tn.esprit.marketsim.trading.TradingDtos.UpdateOrderRequest;
import tn.esprit.marketsim.user.User;

/** Service des ordres avec des repositories simules (pas de base de donnees). */
@ExtendWith(MockitoExtension.class)
class TradeOrderServiceTest {

    @Mock
    private TradeOrderRepository repository;
    @Mock
    private PriceProvider prices;

    private TradeOrderService service;
    private User alice;

    @BeforeEach
    void setUp() {
        service = new TradeOrderService(repository, prices);
        alice = user(1L);
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
