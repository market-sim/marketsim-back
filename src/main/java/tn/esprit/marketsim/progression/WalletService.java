package tn.esprit.marketsim.progression;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.ledger.AuditLedger;
import tn.esprit.marketsim.progression.ProgressionRules.Stats;
import tn.esprit.marketsim.trading.Money;
import tn.esprit.marketsim.trading.TradingProperties;
import tn.esprit.marketsim.trading.TradingUserRepository;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;

/**
 * Portefeuille UNIQUE de l'utilisateur : {@code users.virtual_balance} (BigDecimal), le meme solde que le trading
 * du module 2. Tous les mouvements du jeu passent ici : pilote automatique (emprunt / remboursement), resultat
 * d'une partie, ajustement de l'administrateur, demande de fonds acceptee, redemarrage apres une ruine.
 * <ul>
 *   <li>chaque mouvement verrouille le compte avec le meme verrou que le trading
 *       ({@link TradingUserRepository#findByIdForUpdate}, SELECT ... FOR UPDATE) : pas de course avec un ordre ;</li>
 *   <li>{@code TraderProfile.balance} n'est plus de l'argent : c'est une COPIE du solde unique, rafraichie a chaque
 *       mouvement et a chaque lecture, qui sert seulement aux regles de niveau ;</li>
 *   <li>chaque mouvement est trace dans le registre d'audit (WALLET_MOVE) apres le commit ; un echec du registre
 *       ne bloque jamais l'operation.</li>
 * </ul>
 */
@Slf4j
@Service
public class WalletService {

    public static final String LEDGER_TYPE = "WALLET_MOVE";

    private final TraderProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final TradingUserRepository accounts;
    private final TradingProperties trading;
    private final AuditLedger ledger;
    private final TransactionTemplate requiresNew;

    @Autowired
    public WalletService(TraderProfileRepository profileRepository, UserRepository userRepository,
                         TradingUserRepository accounts, TradingProperties trading, AuditLedger ledger,
                         PlatformTransactionManager txManager) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.accounts = accounts;
        this.trading = trading;
        this.ledger = ledger;
        if (txManager != null) {
            this.requiresNew = new TransactionTemplate(txManager);
            this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            this.requiresNew = null;
        }
    }

    /** Raison d'un mouvement (enregistree dans le registre d'audit). */
    public enum Reason { AUTOPILOT_DEBIT, AUTOPILOT_CREDIT, GAME_RESULT, ADMIN_SET, RESTART }

    /** Un mouvement applique : ancien et nouveau solde. */
    public record Movement(BigDecimal before, BigDecimal after) {
        public BigDecimal delta() {
            return after.subtract(before);
        }
    }

    /** Ancien et nouveau solde, ancien et nouveau niveau apres un ajustement de l'administrateur. */
    public record Adjustment(double before, double after, UserLevel levelBefore, UserLevel levelAfter) {
    }

    // ===================== Lecture =====================

    /** Solde unique (sans verrou). Un compte sans solde enregistre a le capital initial. */
    @Transactional(readOnly = true)
    public BigDecimal cash(Long userId) {
        return accounts.findById(userId).map(this::cashOf)
                .orElseThrow(() -> new NotFoundException("Compte introuvable"));
    }

    public BigDecimal cashOf(User u) {
        return u.getVirtualBalance() == null ? Money.amount(trading.getInitialCapital()) : Money.amount(u.getVirtualBalance());
    }

    @Transactional
    public double balance(Long userId) {
        return cash(userId).doubleValue();
    }

    @Transactional
    public UserLevel level(Long userId) {
        return profile(userId).getLevel();
    }

    /** Recopie le solde unique dans le profil du jeu (sans sauvegarder) : les regles de niveau lisent cette copie. */
    public TraderProfile mirror(TraderProfile p, BigDecimal cash) {
        Stats s = p.stats();
        double c = cash.doubleValue();
        if (Math.abs(s.balance() - c) > 1e-9) {
            p.setStats(new Stats(c, s.games(), s.trades(), s.wins(), s.grossWin(), s.grossLoss(), s.disciplinedTrades(),
                    s.sumMaxDrawdown()));
        }
        return p;
    }

    // ===================== Mouvements =====================

    /** Retire un montant (pilote automatique) ; erreur 409 si le solde est insuffisant. Renvoie le nouveau solde. */
    @Transactional
    public double debit(Long userId, double amount) {
        if (amount <= 0) return balance(userId);
        // l'argent prete au pilote reste au joueur : le niveau ne bouge pas avant le remboursement
        return apply(userId, Money.amount(BigDecimal.valueOf(amount)).negate(), Reason.AUTOPILOT_DEBIT, false, null)
                .after().doubleValue();
    }

    /** Ajoute un montant (retour du pilote automatique) et renvoie le nouveau solde. */
    @Transactional
    public double credit(Long userId, double amount) {
        if (amount <= 0) return balance(userId);
        return apply(userId, Money.amount(BigDecimal.valueOf(amount)), Reason.AUTOPILOT_CREDIT, true, null)
                .after().doubleValue();
    }

    /**
     * L'administrateur fixe le solde (correction, bonus, demande de fonds acceptee...). Le niveau est recalcule avec
     * les regles habituelles. Le record (peakBalance) n'est pas touche : un cadeau ne donne pas de badge.
     */
    @Transactional
    public Adjustment adminSet(Long userId, double newBalance) {
        TraderProfile p = profile(userId);
        UserLevel levelBefore = p.getLevel();
        double peak = p.getPeakBalance();
        Movement m = setTo(userId, Money.amount(BigDecimal.valueOf(newBalance)), Reason.ADMIN_SET);
        TraderProfile after = profile(userId);
        after.setPeakBalance(peak);
        profileRepository.save(after);
        return new Adjustment(m.before().doubleValue(), m.after().doubleValue(), levelBefore, after.getLevel());
    }

    /** Fixe le solde a une valeur (ajustement admin, redemarrage). */
    @Transactional
    public Movement setTo(Long userId, BigDecimal target, Reason reason) {
        User account = lock(userId);
        BigDecimal before = cashOf(account);
        return apply(account, Money.amount(target).subtract(before), reason, true, null);
    }

    /**
     * Applique un ecart au solde unique, sous verrou. Un solde ne devient jamais negatif : un debit superieur au
     * solde est refuse (409), sauf pour un resultat de partie, borne a 0.
     */
    @Transactional
    public Movement apply(Long userId, BigDecimal delta, Reason reason, boolean recomputeLevel, Map<String, Object> details) {
        return apply(lock(userId), delta, reason, recomputeLevel, details);
    }

    private Movement apply(User account, BigDecimal delta, Reason reason, boolean recomputeLevel, Map<String, Object> details) {
        if (account.getRole() == Role.ADMIN) {
            throw new ConflictException("Un administrateur n'a pas de portefeuille de trading.");
        }
        BigDecimal before = cashOf(account);
        BigDecimal after = Money.amount(before.add(delta));
        if (after.signum() < 0) {
            if (reason == Reason.GAME_RESULT) {
                after = Money.zero();
            } else {
                throw new ConflictException(String.format(Locale.FRANCE,
                        "Solde insuffisant : ton portefeuille contient %,.2f $.", before));
            }
        }
        // entite geree (chargee sous verrou dans la transaction) : ecrite au commit
        account.setVirtualBalance(after);

        TraderProfile p = mirror(profile(account.getId()), after);
        UserLevel level = recomputeLevel ? ProgressionRules.levelFor(p.stats()) : p.getLevel();
        p.setLevel(level);
        if (p.getBestLevel() == null || level.ordinal() > p.getBestLevel().ordinal()) p.setBestLevel(level);
        p.setUpdatedAt(Instant.now());
        profileRepository.save(p);
        if (account.getLevel() != level) account.setLevel(level);
        audit(account.getId(), reason, before, after, details);
        return new Movement(before, after);
    }

    private User lock(Long userId) {
        return accounts.findByIdForUpdate(userId).orElseThrow(() -> new NotFoundException("Compte introuvable"));
    }

    /** Profil du jeu (cree au besoin), avec la copie du solde a jour. */
    TraderProfile profile(Long userId) {
        TraderProfile p = profileRepository.findByUserId(userId).orElseGet(() -> {
            TraderProfile fresh = TraderProfile.builder()
                    .userId(userId).level(UserLevel.DEBUTANT).bestLevel(UserLevel.DEBUTANT)
                    .updatedAt(Instant.now()).build();
            fresh.setStats(Stats.fresh());
            return profileRepository.save(fresh);
        });
        accounts.findById(userId).ifPresent(u -> mirror(p, cashOf(u)));
        return p;
    }

    /** Trace du mouvement dans le registre d'audit, apres le commit, sans jamais bloquer l'operation. */
    private void audit(Long userId, Reason reason, BigDecimal before, BigDecimal after, Map<String, Object> details) {
        if (ledger == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("reason", reason.name());
        payload.put("before", before.toPlainString());
        payload.put("after", after.toPlainString());
        payload.put("delta", after.subtract(before).toPlainString());
        if (details != null) payload.putAll(details);
        String title = String.format(Locale.FRANCE, "Portefeuille %s : %,.2f $ → %,.2f $", reason.name(), before, after);
        Runnable write = () -> {
            try {
                if (requiresNew != null) requiresNew.executeWithoutResult(s -> ledger.record(LEDGER_TYPE, null, userId, null, title, payload));
                else ledger.record(LEDGER_TYPE, null, userId, null, title, payload);
            } catch (RuntimeException e) {
                log.warn("Audit du mouvement de portefeuille de {} impossible : {}", userId, e.getMessage());
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
}
