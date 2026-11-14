package tn.esprit.marketsim.progression;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.notification.Notification;
import tn.esprit.marketsim.notification.NotificationService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.TraceDetails;

/**
 * Modification du portefeuille d'un trader par l'administrateur. Toujours tracee : motif obligatoire, ancien et
 * nouveau montant dans le journal d'activite, et le trader est prevenu dans sa cloche.
 */
@Service
@RequiredArgsConstructor
public class AdminWalletService {

    public static final double MAX_BALANCE = 1_000_000;

    private final WalletService wallet;
    private final UserRepository users;
    private final ActivityLogService activityLog;
    private final NotificationService notifications;

    public record SetBalanceRequest(Double balance, String reason) {
    }

    @Transactional
    public WalletService.Adjustment setBalance(User admin, Long userId, SetBalanceRequest req) {
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        if (target.getRole() == Role.ADMIN) {
            throw new ConflictException("Un administrateur n'a pas de portefeuille de trading.");
        }
        if (Boolean.TRUE.equals(target.getDeleted())) throw new ConflictException("Ce compte est supprimé.");
        if (req == null || req.balance() == null || req.balance().isNaN() || req.balance().isInfinite()) {
            throw new IllegalArgumentException("Montant manquant");
        }
        double amount = Math.round(req.balance() * 100) / 100.0;
        if (amount < 0 || amount > MAX_BALANCE) {
            throw new IllegalArgumentException("Le montant doit être entre 0 et 1 000 000 $");
        }
        String reason = req.reason() == null ? "" : req.reason().replaceAll("\\p{Cntrl}", " ").strip();
        if (reason.length() < 3) throw new IllegalArgumentException("Indique le motif de la modification (3 caractères minimum)");
        if (reason.length() > 200) reason = reason.substring(0, 200);

        WalletService.Adjustment adj = wallet.adminSet(userId, amount);
        TraceDetails details = TraceDetails.empty()
                .add("balance", adj.before(), adj.after())
                .add("level", adj.levelBefore(), adj.levelAfter())
                .add("reason", null, reason);
        activityLog.recordAfterCommit(admin.getId(), userId, ActivityAction.ADMIN_SET_BALANCE, "OK", details);
        notifications.notify(userId, Notification.Type.INFO,
                String.format(Locale.FRANCE, "Portefeuille ajusté : %,.2f $ → %,.2f $", adj.before(), adj.after()),
                "Modification faite par l'administrateur. Motif : " + reason
                        + (adj.levelBefore() != adj.levelAfter() ? " · Nouveau niveau : " + adj.levelAfter() : ""),
                "/progression");
        return adj;
    }
}
