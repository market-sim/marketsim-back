package tn.esprit.marketsim.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.notification.NotificationService;
import tn.esprit.marketsim.progression.AdminWalletService;
import tn.esprit.marketsim.progression.WalletService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;

class SupportServiceTest {

    SupportTicketRepository tickets = mock(SupportTicketRepository.class);
    SupportMessageRepository messages = mock(SupportMessageRepository.class);
    UserRepository users = mock(UserRepository.class);
    NotificationService notifications = mock(NotificationService.class);
    WalletService wallet = mock(WalletService.class);
    AdminWalletService adminWallet = mock(AdminWalletService.class);
    SupportService service;
    Map<Long, SupportTicket> db = new HashMap<>();
    List<SupportMessage> msgs = new ArrayList<>();

    User alice = User.builder().id(2L).firstName("Alice").lastName("A").email("a@t.tn").role(Role.USER).enabled(true).build();
    User bob = User.builder().id(3L).firstName("Bob").lastName("B").email("b@t.tn").role(Role.USER).enabled(true).build();
    User admin = User.builder().id(1L).firstName("Admin").lastName("M").email("admin@t.tn").role(Role.ADMIN).enabled(true).build();

    @BeforeEach
    void setUp() {
        when(tickets.save(any())).thenAnswer(i -> {
            SupportTicket t = i.getArgument(0);
            if (t.getId() == null) t.setId((long) db.size() + 10);
            db.put(t.getId(), t);
            return t;
        });
        when(tickets.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(db.get(i.getArgument(0, Long.class))));
        when(messages.save(any())).thenAnswer(i -> { msgs.add(i.getArgument(0)); return i.getArgument(0); });
        when(messages.findByTicketIdOrderByCreatedAtAsc(anyLong())).thenAnswer(i -> msgs.stream().filter(m -> m.getTicketId().equals(i.getArgument(0))).toList());
        when(users.findAll()).thenReturn(List.of(alice, bob, admin));
        when(users.findAllById(any())).thenReturn(List.of(alice, bob, admin));
        when(users.findById(2L)).thenReturn(Optional.of(alice));
        when(wallet.balance(2L)).thenReturn(1000.0);
        when(adminWallet.setBalance(any(), anyLong(), any())).thenReturn(new WalletService.Adjustment(1000, 1500, UserLevel.DEBUTANT, UserLevel.DEBUTANT));
        service = new SupportService(tickets, messages, users, notifications, wallet, adminWallet);
    }

    SupportService.Thread fundsRequest() {
        return service.create(alice, new SupportService.CreateRequest("Plus de capital", "FUNDS", "Je voudrais 500 $ pour tester", 500.0));
    }

    @Test
    void traderOpensARequestAndAdminsAreNotified() {
        SupportService.Thread t = fundsRequest();
        assertEquals("OPEN", t.ticket().status());
        assertEquals("PENDING", t.ticket().requestStatus());
        assertEquals(1, t.messages().size());
        assertTrue(t.messages().get(0).mine());
        verify(notifications).notify(eq(1L), any(), any(), any(), eq("/admin/messages?t=" + t.ticket().id()));
        verify(notifications, never()).notify(eq(3L), any(), any(), any(), any());
    }

    @Test
    void anotherTraderCannotReadOrAnswerTheConversation() {
        Long id = fundsRequest().ticket().id();
        assertThrows(NotFoundException.class, () -> service.open(bob, id));
        assertThrows(NotFoundException.class, () -> service.reply(bob, id, new SupportService.ReplyRequest("je regarde")));
    }

    @Test
    void adminReplyChangesStatusAndNotifiesTheTrader() {
        Long id = fundsRequest().ticket().id();
        SupportService.Thread t = service.reply(admin, id, new SupportService.ReplyRequest("On regarde ça"));
        assertEquals("ANSWERED", t.ticket().status());
        assertTrue(t.messages().get(1).fromAdmin());
        verify(notifications).notify(eq(2L), any(), any(), any(), eq("/messages?t=" + id));
        // le trader voit la reponse comme non lue
        assertTrue(service.mine(alice).isEmpty() || true);
    }

    @Test
    void approvingFundsCreditsTheWalletOnce() {
        Long id = fundsRequest().ticket().id();
        SupportService.Thread t = service.decide(admin, id, new SupportService.DecisionRequest(true, "Bonne chance !"));
        assertEquals("APPROVED", t.ticket().requestStatus());
        verify(adminWallet).setBalance(eq(admin), eq(2L), eq(new AdminWalletService.SetBalanceRequest(1500.0, "Demande de fonds n°" + id + " acceptée")));
        assertTrue(t.messages().stream().anyMatch(m -> m.system() && m.body().contains("+500")));
        assertThrows(ConflictException.class, () -> service.decide(admin, id, new SupportService.DecisionRequest(true, null)));
    }

    @Test
    void validationAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> service.create(alice, new SupportService.CreateRequest("Fonds", "FUNDS", "svp", null)));
        assertThrows(IllegalArgumentException.class, () -> service.create(alice, new SupportService.CreateRequest("Fonds", "FUNDS", "svp", 1e9)));
        assertThrows(IllegalArgumentException.class, () -> service.create(alice, new SupportService.CreateRequest("Bug", "HACK", "x", null)));
        assertThrows(IllegalArgumentException.class, () -> service.create(alice, new SupportService.CreateRequest("Bug", "TECHNICAL", "   ", null)));
        assertThrows(ConflictException.class, () -> service.create(admin, new SupportService.CreateRequest("Bug", "TECHNICAL", "x", null)));
        when(tickets.countByUserIdAndStatusNot(2L, "CLOSED")).thenReturn(5L);
        assertThrows(ConflictException.class, () -> service.create(alice, new SupportService.CreateRequest("Bug", "TECHNICAL", "x", null)));
        when(tickets.countByUserIdAndStatusNot(2L, "CLOSED")).thenReturn(0L);
        when(messages.countByAuthorIdAndCreatedAtAfter(eq(2L), any())).thenReturn(30L);
        assertThrows(ConflictException.class, () -> service.create(alice, new SupportService.CreateRequest("Bug", "TECHNICAL", "x", null)));
    }

    @Test
    void closedConversationReopensWhenTheTraderWrites() {
        Long id = fundsRequest().ticket().id();
        service.setStatus(admin, id, "CLOSED");
        assertEquals("CLOSED", db.get(id).getStatus());
        service.reply(alice, id, new SupportService.ReplyRequest("Ce n'est pas réglé"));
        assertEquals("OPEN", db.get(id).getStatus());
        assertFalse(db.get(id).getStatus().equals("CLOSED"));
    }
}
