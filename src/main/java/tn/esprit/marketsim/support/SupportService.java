package tn.esprit.marketsim.support;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.notification.Notification;
import tn.esprit.marketsim.notification.NotificationService;
import tn.esprit.marketsim.progression.AdminWalletService;
import tn.esprit.marketsim.progression.WalletService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;

/**
 * Messagerie de support trader <-> administration. Un trader ne voit que ses conversations ; l'administration
 * les voit toutes, repond, change l'etat et peut accepter une demande de fonds (le portefeuille est credite avec
 * une trace dans le journal d'activite). Chaque nouveau message envoie une notification a l'autre partie.
 */
@Service
@RequiredArgsConstructor
public class SupportService {

    static final Set<String> CATEGORIES = Set.of("FUNDS", "TECHNICAL", "ACCOUNT", "OTHER");
    static final Set<String> STATUSES = Set.of(SupportTicket.OPEN, SupportTicket.ANSWERED, SupportTicket.CLOSED);
    static final int MAX_OPEN_TICKETS = 5;
    static final int MAX_MESSAGES_PER_HOUR = 30;
    static final int MAX_BODY = 2000;
    static final double MAX_REQUEST = 100_000;

    private final SupportTicketRepository tickets;
    private final SupportMessageRepository messages;
    private final UserRepository users;
    private final NotificationService notifications;
    private final WalletService wallet;
    private final AdminWalletService adminWallet;

    public record CreateRequest(String subject, String category, String body, Double amount) {
    }

    public record ReplyRequest(String body) {
    }

    public record DecisionRequest(boolean approve, String note) {
    }

    public record TicketView(Long id, String subject, String category, String status, Double requestedAmount,
                             String requestStatus, Instant createdAt, Instant lastMessageAt, boolean unread,
                             String lastMessage, boolean lastFromAdmin, Long userId, String userName, String userEmail,
                             String userAvatarUrl, Double userBalance) {
    }

    public record MessageView(Long id, boolean fromAdmin, boolean system, boolean mine, String authorName, String body,
                              Instant createdAt) {
    }

    public record Thread(TicketView ticket, List<MessageView> messages) {
    }

    // ─── lecture ─────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TicketView> mine(User user) {
        return tickets.findByUserIdOrderByLastMessageAtDesc(user.getId()).stream().map(t -> view(t, user, false, null)).toList();
    }

    @Transactional(readOnly = true)
    public List<TicketView> all(User admin, String status, String category) {
        requireAdmin(admin);
        List<SupportTicket> list = tickets.findTop300ByOrderByLastMessageAtDesc().stream()
                .filter(t -> status == null || status.isBlank() || "ALL".equals(status) || t.getStatus().equals(status))
                .filter(t -> category == null || category.isBlank() || "ALL".equals(category) || t.getCategory().equals(category))
                .toList();
        Map<Long, User> owners = users.findAllById(list.stream().map(SupportTicket::getUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream().map(t -> view(t, admin, true, owners.get(t.getUserId()))).toList();
    }

    /** Nombre de conversations avec un message non lu (badge du menu). */
    @Transactional(readOnly = true)
    public long unreadCount(User user) {
        boolean admin = user.getRole() == Role.ADMIN;
        List<SupportTicket> list = admin ? tickets.findTop300ByOrderByLastMessageAtDesc() : tickets.findByUserIdOrderByLastMessageAtDesc(user.getId());
        return list.stream().filter(t -> unread(t, admin)).count();
    }

    /** Ouvre une conversation et la marque comme lue pour celui qui la lit. */
    @Transactional
    public Thread open(User user, Long id) {
        SupportTicket t = accessible(user, id);
        boolean admin = user.getRole() == Role.ADMIN;
        if (admin) t.setAdminReadAt(Instant.now());
        else t.setUserReadAt(Instant.now());
        tickets.save(t);
        return thread(t, user);
    }

    // ─── ecriture ────────────────────────────────────────────────────────────────────────────

    @Transactional
    public Thread create(User user, CreateRequest req) {
        if (user.getRole() == Role.ADMIN) throw new ConflictException("L'administration répond aux conversations : elle n'en ouvre pas.");
        if (tickets.countByUserIdAndStatusNot(user.getId(), SupportTicket.CLOSED) >= MAX_OPEN_TICKETS) {
            throw new ConflictException("Tu as déjà " + MAX_OPEN_TICKETS + " conversations ouvertes : attends une réponse avant d'en ouvrir une autre.");
        }
        String category = req.category() == null ? "" : req.category().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("Choisis le type de demande");
        String subject = clean(req.subject(), 120);
        if (subject.length() < 3) throw new IllegalArgumentException("Le sujet fait au moins 3 caractères");
        String body = body(req.body());
        rateLimit(user);

        SupportTicket t = new SupportTicket();
        t.setUserId(user.getId());
        t.setSubject(subject);
        t.setCategory(category);
        t.setStatus(SupportTicket.OPEN);
        if ("FUNDS".equals(category)) {
            if (req.amount() == null || req.amount().isNaN() || req.amount() < 1 || req.amount() > MAX_REQUEST) {
                throw new IllegalArgumentException("Indique le montant demandé (entre 1 et 100 000 $)");
            }
            t.setRequestedAmount(Math.round(req.amount() * 100) / 100.0);
            t.setRequestStatus("PENDING");
        }
        Instant now = Instant.now();
        t.setCreatedAt(now);
        t.setUserReadAt(now);
        tickets.save(t);
        add(t, user.getId(), false, false, body);
        notifyAdmins(user, t, "Nouvelle demande : " + subject);
        return thread(t, user);
    }

    @Transactional
    public Thread reply(User user, Long id, ReplyRequest req) {
        SupportTicket t = accessible(user, id);
        boolean admin = user.getRole() == Role.ADMIN;
        String body = body(req.body());
        rateLimit(user);
        add(t, user.getId(), admin, false, body);
        if (admin) {
            t.setStatus(SupportTicket.ANSWERED);
            t.setAdminReadAt(Instant.now());
            notifications.notify(t.getUserId(), Notification.Type.INFO, "Réponse du support : " + t.getSubject(),
                    preview(body), "/messages?t=" + t.getId());
        } else {
            t.setStatus(SupportTicket.OPEN); // un message du trader rouvre aussi une conversation fermee
            t.setUserReadAt(Instant.now());
            notifyAdmins(user, t, "Nouveau message : " + t.getSubject());
        }
        tickets.save(t);
        return thread(t, user);
    }

    @Transactional
    public Thread setStatus(User admin, Long id, String status) {
        requireAdmin(admin);
        SupportTicket t = accessible(admin, id);
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("État inconnu");
        if (!status.equals(t.getStatus())) {
            t.setStatus(status);
            if (SupportTicket.CLOSED.equals(status)) {
                add(t, null, true, true, "Conversation marquée comme résolue. Tu peux la rouvrir en répondant.");
                notifications.notify(t.getUserId(), Notification.Type.INFO, "Demande résolue : " + t.getSubject(),
                        "L'administration a clôturé ta conversation. Réponds-y si le problème n'est pas réglé.", "/messages?t=" + t.getId());
            }
            tickets.save(t);
        }
        return thread(t, admin);
    }

    /** Accepter (le portefeuille est credite du montant demande) ou refuser une demande de fonds. */
    @Transactional
    public Thread decide(User admin, Long id, DecisionRequest req) {
        requireAdmin(admin);
        SupportTicket t = accessible(admin, id);
        if (!"FUNDS".equals(t.getCategory()) || t.getRequestedAmount() == null) throw new ConflictException("Ce n'est pas une demande de fonds");
        if (!"PENDING".equals(t.getRequestStatus())) throw new ConflictException("Cette demande a déjà été traitée");
        String note = req.note() == null ? "" : clean(req.note(), MAX_BODY);
        String text;
        if (req.approve()) {
            double before = wallet.balance(t.getUserId());
            WalletService.Adjustment adj = adminWallet.setBalance(admin, t.getUserId(), new AdminWalletService.SetBalanceRequest(
                    before + t.getRequestedAmount(), "Demande de fonds n°" + t.getId() + " acceptée"));
            t.setRequestStatus("APPROVED");
            text = String.format(Locale.FRANCE, "✅ Demande acceptée : +%,.2f $ ajoutés à ton portefeuille (nouveau solde %,.2f $).",
                    t.getRequestedAmount(), adj.after());
        } else {
            t.setRequestStatus("REJECTED");
            text = String.format(Locale.FRANCE, "❌ Demande de %,.2f $ refusée.", t.getRequestedAmount());
        }
        add(t, null, true, true, text);
        if (!note.isBlank()) add(t, admin.getId(), true, false, note);
        t.setStatus(SupportTicket.ANSWERED);
        t.setAdminReadAt(Instant.now());
        tickets.save(t);
        notifications.notify(t.getUserId(), Notification.Type.INFO,
                req.approve() ? "Demande de fonds acceptée ✅" : "Demande de fonds refusée", text + (note.isBlank() ? "" : " " + note),
                "/messages?t=" + t.getId());
        return thread(t, admin);
    }

    // ─── utilitaires ─────────────────────────────────────────────────────────────────────────

    private void add(SupportTicket t, Long authorId, boolean fromAdmin, boolean system, String body) {
        SupportMessage m = new SupportMessage();
        m.setTicketId(t.getId());
        m.setAuthorId(authorId);
        m.setFromAdmin(fromAdmin);
        m.setSystem(system);
        m.setBody(body);
        Instant now = Instant.now();
        m.setCreatedAt(now);
        messages.save(m);
        t.setLastMessageAt(now);
        if (fromAdmin) t.setLastAdminMessageAt(now);
        else t.setLastUserMessageAt(now);
    }

    /** Un trader n'accede qu'a ses propres conversations (sinon « introuvable », sans dire qu'elle existe). */
    private SupportTicket accessible(User user, Long id) {
        SupportTicket t = tickets.findById(id).orElseThrow(() -> new NotFoundException("Conversation introuvable"));
        if (user.getRole() != Role.ADMIN && !t.getUserId().equals(user.getId())) throw new NotFoundException("Conversation introuvable");
        return t;
    }

    private static void requireAdmin(User u) {
        if (u.getRole() != Role.ADMIN) throw new org.springframework.security.access.AccessDeniedException("Réservé à l'administration");
    }

    private void rateLimit(User user) {
        if (messages.countByAuthorIdAndCreatedAtAfter(user.getId(), Instant.now().minus(Duration.ofHours(1))) >= MAX_MESSAGES_PER_HOUR) {
            throw new ConflictException("Trop de messages en peu de temps : réessaie dans un moment.");
        }
    }

    private void notifyAdmins(User from, SupportTicket t, String title) {
        String who = from.getFirstName() + " " + from.getLastName();
        users.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN && u.isEnabled() && !Boolean.TRUE.equals(u.getDeleted()))
                .forEach(a -> notifications.notify(a.getId(), Notification.Type.INFO, title,
                        who + (t.getRequestedAmount() != null && "PENDING".equals(t.getRequestStatus())
                                ? String.format(Locale.FRANCE, " demande %,.2f $.", t.getRequestedAmount()) : " t'a écrit."),
                        "/admin/messages?t=" + t.getId()));
    }

    private static boolean unread(SupportTicket t, boolean admin) {
        Instant last = admin ? t.getLastUserMessageAt() : t.getLastAdminMessageAt();
        Instant read = admin ? t.getAdminReadAt() : t.getUserReadAt();
        return last != null && (read == null || last.isAfter(read));
    }

    private TicketView view(SupportTicket t, User viewer, boolean admin, User owner) {
        SupportMessage last = messages.findFirstByTicketIdOrderByCreatedAtDesc(t.getId());
        Double balance = null;
        if (admin && owner != null && "FUNDS".equals(t.getCategory())) balance = wallet.balance(owner.getId());
        return new TicketView(t.getId(), t.getSubject(), t.getCategory(), t.getStatus(), t.getRequestedAmount(), t.getRequestStatus(),
                t.getCreatedAt(), t.getLastMessageAt(), unread(t, admin), last == null ? null : preview(last.getBody()),
                last != null && last.isFromAdmin(),
                admin && owner != null ? owner.getId() : null,
                admin && owner != null ? owner.getFirstName() + " " + owner.getLastName() : null,
                admin && owner != null ? owner.getEmail() : null,
                admin && owner != null ? owner.getAvatarUrl() : null, balance);
    }

    private Thread thread(SupportTicket t, User viewer) {
        boolean admin = viewer.getRole() == Role.ADMIN;
        User owner = admin ? users.findById(t.getUserId()).orElse(null) : viewer;
        List<SupportMessage> list = messages.findByTicketIdOrderByCreatedAtAsc(t.getId());
        Map<Long, User> authors = users.findAllById(list.stream().map(SupportMessage::getAuthorId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<MessageView> views = list.stream().map(m -> {
            User a = m.getAuthorId() == null ? null : authors.get(m.getAuthorId());
            String name = m.isSystem() ? "MarketSim" : m.isFromAdmin() ? "Support MarketSim" + (a == null ? "" : " · " + a.getFirstName())
                    : a == null ? "Trader" : a.getFirstName() + " " + a.getLastName();
            return new MessageView(m.getId(), m.isFromAdmin(), m.isSystem(), m.getAuthorId() != null && m.getAuthorId().equals(viewer.getId()),
                    name, m.getBody(), m.getCreatedAt());
        }).toList();
        return new Thread(view(t, viewer, admin, owner), views);
    }

    private static String body(String raw) {
        String b = raw == null ? "" : raw.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").strip();
        if (b.isEmpty()) throw new IllegalArgumentException("Le message est vide");
        if (b.length() > MAX_BODY) throw new IllegalArgumentException("Message trop long (2 000 caractères maximum)");
        return b;
    }

    private static String clean(String s, int max) {
        String c = s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").strip();
        return c.length() > max ? c.substring(0, max) : c;
    }

    private static String preview(String s) {
        String p = s.replace('\n', ' ');
        return p.length() > 120 ? p.substring(0, 117) + "…" : p;
    }
}
