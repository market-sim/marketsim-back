package tn.esprit.marketsim.notification;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.notification.Notification.Type;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.account.MailService;
import tn.esprit.marketsim.user.profile.PreferencesService;
import tn.esprit.marketsim.user.profile.UserPreferences;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final PreferencesService preferences;
    private final MailService mail;
    private final UserRepository users;

    @org.springframework.beans.factory.annotation.Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl = "http://localhost:5173";

    /** Alertes de securite (connexion, mot de passe, double authentification, passkeys). */
    static boolean security(Type t) {
        return t == Type.NEW_DEVICE || t == Type.PASSWORD_CHANGED || t == Type.TWO_FACTOR;
    }

    /** Progression dans le jeu (niveau, portefeuille, pilote automatique). */
    static boolean progress(Type t) {
        return t == Type.LEVEL_UP || t == Type.LEVEL_DOWN || t == Type.LOW_BALANCE || t == Type.BANKRUPT || t == Type.AUTOPILOT;
    }

    public record NotificationView(Long id, Type type, String title, String message, String link, Instant createdAt,
                                   boolean read) {
    }

    public record NotificationList(long unread, List<NotificationView> items) {
    }

    /**
     * Cree une notification. Une notification ne doit jamais faire echouer l'action qui la declenche :
     * elle est ecrite dans sa propre transaction et les erreurs sont ignorees.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(Long userId, Type type, String title, String message, String link) {
        if (userId == null) return;
        UserPreferences prefs = null;
        try {
            prefs = preferences.get(userId);
        } catch (RuntimeException ignored) {
            // sans preferences : comportement par defaut (tout dans la cloche, pas d'e-mail de progression)
        }
        boolean inApp = prefs == null || (security(type) ? prefs.isInAppSecurity() : !progress(type) || prefs.isInAppProgress());
        boolean email = prefs != null && (security(type) ? prefs.isEmailSecurity() : progress(type) && prefs.isEmailProgress());
        if (email) {
            try {
                users.findById(userId).ifPresent(u -> mail.send(u.getEmail(), title, message,
                        link == null ? null : frontendUrl + link, security(type) ? "SECURITY" : "PROGRESS"));
            } catch (RuntimeException ignored) {
                // l'e-mail est un bonus
            }
        }
        if (!inApp) return;
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setType(type);
            n.setTitle(title.length() > 120 ? title.substring(0, 120) : title);
            n.setMessage(message == null ? null : message.length() > 500 ? message.substring(0, 500) : message);
            n.setLink(link);
            n.setCreatedAt(Instant.now());
            repository.save(n);
        } catch (RuntimeException ignored) {
            // la notification est un bonus, pas une condition
        }
    }

    @Transactional(readOnly = true)
    public NotificationList list(Long userId) {
        return new NotificationList(repository.countByUserIdAndReadAtIsNull(userId),
                repository.findTop30ByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(n -> new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getLink(),
                                n.getCreatedAt(), n.getReadAt() != null))
                        .toList());
    }

    /**
     * Historique complet (200 dernieres) avec filtre : ALL, UNREAD, SECURITY, PROGRESS ou INFO.
     */
    @Transactional(readOnly = true)
    public NotificationList history(Long userId, String filter) {
        String f = filter == null ? "ALL" : filter.toUpperCase();
        List<NotificationView> items = repository.findTop200ByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(n -> switch (f) {
                    case "UNREAD" -> n.getReadAt() == null;
                    case "SECURITY" -> security(n.getType());
                    case "PROGRESS" -> progress(n.getType());
                    case "INFO" -> n.getType() == Type.INFO;
                    default -> true;
                })
                .map(n -> new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getLink(),
                        n.getCreatedAt(), n.getReadAt() != null))
                .toList();
        return new NotificationList(repository.countByUserIdAndReadAtIsNull(userId), items);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Notification n = repository.findById(id).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Notification introuvable"));
        repository.delete(n);
    }

    /** Supprime les notifications deja lues ; renvoie le nombre supprime. */
    @Transactional
    public int deleteRead(Long userId) {
        List<Notification> read = repository.findByUserIdAndReadAtIsNotNull(userId);
        repository.deleteAll(read);
        return read.size();
    }

    @Transactional
    public void markRead(Long userId, Long id) {
        Notification n = repository.findById(id).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Notification introuvable"));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repository.save(n);
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        Instant now = Instant.now();
        for (Notification n : repository.findByUserIdAndReadAtIsNull(userId)) {
            n.setReadAt(now);
            repository.save(n);
        }
    }
}
