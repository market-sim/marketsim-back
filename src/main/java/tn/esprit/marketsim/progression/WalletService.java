package tn.esprit.marketsim.progression;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.progression.ProgressionRules.Stats;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;

/**
 * Mouvements d'argent du portefeuille du jeu en dehors des parties : le pilote automatique emprunte une partie
 * du portefeuille au demarrage et la rend (avec son gain ou sa perte) a l'arret. Le niveau est recalcule a chaque
 * mouvement : une perte du pilote peut faire redescendre le joueur.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final TraderProfileRepository profileRepository;
    private final UserRepository userRepository;

    @Transactional
    public double balance(Long userId) {
        return profile(userId).getBalance();
    }

    @Transactional
    public UserLevel level(Long userId) {
        return profile(userId).getLevel();
    }

    /** Retire un montant du portefeuille (erreur si le solde est insuffisant) et renvoie le nouveau solde. */
    @Transactional
    public double debit(Long userId, double amount) {
        if (amount <= 0) return balance(userId);
        TraderProfile p = profile(userId);
        if (amount > p.getBalance() + 1e-9) {
            throw new ConflictException(String.format(java.util.Locale.FRANCE,
                    "Solde insuffisant : ton portefeuille contient %,.2f $.", p.getBalance()));
        }
        // l'argent prete au pilote reste au joueur : le niveau ne bouge pas avant le remboursement
        return change(p, -amount, false);
    }

    /** Ajoute un montant (retour du pilote automatique) et renvoie le nouveau solde. */
    @Transactional
    public double credit(Long userId, double amount) {
        if (amount <= 0) return balance(userId);
        return change(profile(userId), amount, true);
    }

    /** Ancien et nouveau solde, ancien et nouveau niveau apres un ajustement de l'administrateur. */
    public record Adjustment(double before, double after, UserLevel levelBefore, UserLevel levelAfter) {
    }

    /**
     * L'administrateur fixe le solde (correction, bonus de demonstration, remise a zero...). Le niveau est recalcule
     * avec les memes regles qu'apres une partie : donner de l'argent ne suffit pas pour monter, il faut aussi le
     * score de competence et le nombre de parties ; descendre sous 1 000 $ ramene au niveau Debutant.
     * Le record du portefeuille (peakBalance) n'est pas touche : un cadeau de l'admin ne donne pas de badge.
     */
    @Transactional
    public Adjustment adminSet(Long userId, double newBalance) {
        TraderProfile p = profile(userId);
        double before = p.getBalance();
        UserLevel levelBefore = p.getLevel();
        double after = change(p, ProgressionRules.round2(newBalance) - before, true);
        return new Adjustment(before, after, levelBefore, p.getLevel());
    }

    private double change(TraderProfile p, double delta, boolean recomputeLevel) {
        Stats s = p.stats();
        Stats next = new Stats(ProgressionRules.round2(Math.max(0, s.balance() + delta)), s.games(), s.trades(), s.wins(),
                s.grossWin(), s.grossLoss(), s.disciplinedTrades(), s.sumMaxDrawdown());
        p.setStats(next);
        UserLevel level = recomputeLevel ? ProgressionRules.levelFor(next) : p.getLevel();
        p.setLevel(level);
        if (p.getBestLevel() == null || level.ordinal() > p.getBestLevel().ordinal()) p.setBestLevel(level);
        p.setUpdatedAt(Instant.now());
        profileRepository.save(p);
        userRepository.findById(p.getUserId()).ifPresent(u -> {
            if (u.getRole() != Role.ADMIN && u.getLevel() != level) {
                u.setLevel(level);
                userRepository.save(u);
            }
        });
        return next.balance();
    }

    private TraderProfile profile(Long userId) {
        return profileRepository.findByUserId(userId).orElseGet(() -> {
            TraderProfile p = TraderProfile.builder()
                    .userId(userId).level(UserLevel.DEBUTANT).bestLevel(UserLevel.DEBUTANT)
                    .updatedAt(Instant.now()).build();
            p.setStats(Stats.fresh());
            return profileRepository.save(p);
        });
    }
}
