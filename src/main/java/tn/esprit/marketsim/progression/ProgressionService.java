package tn.esprit.marketsim.progression;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentConfigService;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.replay.ReplayService;
import tn.esprit.marketsim.agent.replay.ReplayService.ReplayRequest;
import tn.esprit.marketsim.agent.replay.ReplayService.ReplayResult;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.progression.ProgressionRules.Difficulty;
import tn.esprit.marketsim.progression.ProgressionRules.Requirement;
import tn.esprit.marketsim.progression.ProgressionRules.Stats;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.AdminTraderScore;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.GameHistoryItem;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.OrderDto;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.ProgressionResponse;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.SettleGameRequest;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.SettleGameResponse;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;

/**
 * Systeme de progression : portefeuille de 1 000 $ au depart, chaque partie le fait monter ou descendre,
 * le niveau suit l'argent ET la qualite du trading, et la difficulte de l'application suit le niveau.
 */
@Service
@RequiredArgsConstructor
public class ProgressionService {

    private final TraderProfileRepository profileRepository;
    private final TraderGameRepository gameRepository;
    private final UserRepository userRepository;
    private final AgentConfigService agentService;
    private final ReplayService replayService;
    private final tn.esprit.marketsim.notification.NotificationService notifications;

    @Transactional
    public ProgressionResponse me(User user) {
        requireTrader(user);
        return toResponse(profileFor(user.getId()));
    }

    /**
     * Etat du trader sans creer son profil s'il n'existe pas encore (lecture seule).
     * Evite que deux requetes simultanees d'un nouveau compte creent deux profils (cle unique sur user_id).
     */
    @Transactional(readOnly = true)
    public Optional<ProgressionResponse> peek(User user) {
        requireTrader(user);
        return profileRepository.findByUserId(user.getId()).map(this::toResponse);
    }

    /** Niveau de trading du joueur s'il a deja joue, sinon vide (utilise par le module 1). */
    @Transactional(readOnly = true)
    public Optional<UserLevel> tradingLevel(Long userId) {
        boolean admin = userRepository.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
        if (admin) return Optional.empty();
        return profileRepository.findByUserId(userId).map(TraderProfile::getLevel);
    }

    /** Difficulte a appliquer pour ce joueur (utilisee aussi par le replay). */
    @Transactional
    public Difficulty difficulty(User user) {
        requireTrader(user);
        return ProgressionRules.difficulty(profileFor(user.getId()).getLevel());
    }

    /** Back-office : score de trading de chaque utilisateur qui a deja un portefeuille. */
    @Transactional(readOnly = true)
    public List<AdminTraderScore> allScores() {
        java.util.Set<Long> admins = new java.util.HashSet<>();
        userRepository.findAll().forEach(u -> {
            if (u.getRole() == Role.ADMIN) admins.add(u.getId());
        });
        return profileRepository.findAll().stream().filter(p -> !admins.contains(p.getUserId())).map(p -> {
            Stats s = p.stats();
            return new AdminTraderScore(p.getUserId(), p.getLevel(), p.getBestLevel(), ProgressionRules.round2(s.balance()),
                    ProgressionRules.skillScore(s), s.games(), s.trades(), s.winRate(), s.discipline(),
                    ProgressionRules.bankrupt(s), p.getRestarts(), p.getUpdatedAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<GameHistoryItem> history(User user) {
        requireTrader(user);
        return gameRepository.findTop100ByUserIdOrderByPlayedAtDesc(user.getId()).stream()
                .map(g -> new GameHistoryItem(g.getId(), g.getSymbol(), g.getDays(), g.getStartBalance(),
                        g.getEndBalance(), g.getTrades(), g.getWins(), g.getMaxDrawdown(), g.getLevelBefore(),
                        g.getLevelAfter(), g.getPlayedAt(),
                        g.getStartBalance() > 0 ? g.getEndBalance() / g.getStartBalance() - 1 : 0,
                        g.getAgentReturn(), g.getBenchmarkReturn(), !Boolean.FALSE.equals(g.getCounted())))
                .toList();
    }

    /**
     * Termine une partie : le serveur regenere le meme marche (meme graine), y rejoue les ordres du joueur
     * avec les frais de son niveau, puis met a jour le portefeuille et le niveau.
     */
    @Transactional
    public SettleGameResponse settle(User user, SettleGameRequest req) {
        requireTrader(user);
        TraderProfile profile = profileFor(user.getId());
        Stats before = profile.stats();
        UserLevel levelBefore = profile.getLevel();
        if (ProgressionRules.bankrupt(before)) {
            throw new ConflictException("Portefeuille vide : recommence avec 1 000 $ avant de jouer.");
        }
        Difficulty diff = ProgressionRules.difficulty(levelBefore);
        AgentConfig agent = agentService.findOne(req.agentId(), user);
        double crash = diff.crashForced() ? diff.crashPct() : (req.crashPct() == null ? 0 : req.crashPct());
        int days = Math.max(diff.minDays(), req.days());
        ReplayResult replay = replayService.replay(agent, new ReplayRequest(req.symbol(), days,
                req.model() == null ? PriceModel.REGIME_SWITCHING : req.model(), req.seed(),
                crash > 0 ? crash : null, before.balance()));

        double[] prices = replay.days().stream().mapToDouble(ReplayService.ReplayDay::price).toArray();
        List<HumanSimulator.Order> orders = req.orders().stream()
                .map((OrderDto o) -> new HumanSimulator.Order(o.day(), o.side(), o.fraction()))
                .toList();
        HumanSimulator.Result sim = HumanSimulator.run(prices, replay.capital(), orders, diff.feeRate(),
                replay.stopPct());
        int wins = (int) sim.tradeReturns().stream().filter(r -> r > 0).count();

        // un marche deja joue (« rejouer le meme marche ») : le futur est connu, la partie ne compte pas
        boolean counted = !gameRepository.existsByUserIdAndSeedAndSymbol(user.getId(), replay.seed(),
                replay.symbol());
        UserLevel levelAfter = levelBefore;
        double endBalance = before.balance();
        if (counted) {
            Stats after = ProgressionRules.apply(before, new ProgressionRules.GameOutcome(sim.finalEquity(),
                    sim.tradeReturns(), sim.disciplinedTrades(), sim.maxDrawdown()));
            levelAfter = ProgressionRules.levelFor(after);
            profile.setStats(after);
            profile.setLevel(levelAfter);
            if (profile.getBestLevel() == null || levelAfter.ordinal() > profile.getBestLevel().ordinal()) {
                profile.setBestLevel(levelAfter);
            }
            profile.setUpdatedAt(Instant.now());
            profileRepository.save(profile);
            syncUserLevel(user.getId(), levelAfter);
            endBalance = after.balance();
        }
        gameRepository.save(TraderGame.builder()
                .userId(user.getId()).symbol(replay.symbol()).seed(replay.seed()).days(replay.horizonDays())
                .startBalance(before.balance()).endBalance(counted ? endBalance : ProgressionRules.round2(sim.finalEquity()))
                .trades(sim.tradeReturns().size()).wins(wins).maxDrawdown(sim.maxDrawdown()).fees(sim.fees())
                .levelBefore(levelBefore).levelAfter(levelAfter).playedAt(Instant.now())
                .agentReturn(replay.agentReturn()).benchmarkReturn(replay.benchmarkReturn()).counted(counted)
                .build());

        double pnl = ProgressionRules.round2(sim.finalEquity() - before.balance());
        boolean up = levelAfter.ordinal() > levelBefore.ordinal();
        boolean down = levelAfter.ordinal() < levelBefore.ordinal();
        if (counted) notifyAfterGame(user.getId(), up, down, levelAfter, profile);
        return new SettleGameResponse(counted, before.balance(), counted ? endBalance : before.balance(), pnl,
                sim.tradeReturns().size(), wins, sim.maxDrawdown(), ProgressionRules.round2(sim.fees()),
                levelBefore, levelAfter, up, down, message(counted, pnl, up, down, levelAfter, profile),
                toResponse(profile));
    }

    /** Apres une ruine : retour a 1 000 $ et au niveau DEBUTANT (le meilleur niveau atteint reste memorise). */
    @Transactional
    public ProgressionResponse restart(User user, boolean force) {
        requireTrader(user);
        TraderProfile profile = profileFor(user.getId());
        if (!force && !ProgressionRules.bankrupt(profile.stats())) {
            throw new ConflictException("Ton portefeuille n'est pas vide : tu peux encore jouer.");
        }
        profile.setStats(Stats.fresh());
        profile.setPeakBalance(ProgressionRules.START_BALANCE);
        profile.setLevel(UserLevel.DEBUTANT);
        profile.setRestarts(profile.getRestarts() + 1);
        profile.setUpdatedAt(Instant.now());
        profileRepository.save(profile);
        syncUserLevel(user.getId(), UserLevel.DEBUTANT);
        return toResponse(profile);
    }

    /** L'administrateur gere la plateforme : il n'a ni portefeuille, ni niveau, ni score de trading. */
    private static void requireTrader(User user) {
        if (user.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'administrateur n'a pas de portefeuille ni de score de trading.");
        }
    }

    private TraderProfile profileFor(Long userId) {
        return profileRepository.findByUserId(userId).orElseGet(() -> {
            TraderProfile p = TraderProfile.builder()
                    .userId(userId).level(UserLevel.DEBUTANT).bestLevel(UserLevel.DEBUTANT)
                    .updatedAt(Instant.now()).build();
            p.setStats(Stats.fresh());
            return profileRepository.save(p);
        });
    }

    private void syncUserLevel(Long userId, UserLevel level) {
        userRepository.findById(userId).ifPresent(u -> {
            if (u.getLevel() != level) {
                u.setLevel(level);
                userRepository.save(u);
            }
        });
    }

    private static String message(boolean counted, double pnl, boolean up, boolean down, UserLevel level,
                                  TraderProfile p) {
        if (!counted) {
            return "Marché déjà joué : c'était un entraînement, ton portefeuille ne change pas.";
        }
        if (ProgressionRules.bankrupt(p.stats())) {
            return "Ton portefeuille est vide. Recommence avec 1 000 $ au niveau Débutant.";
        }
        if (up) return "Bravo ! Tu passes au niveau " + label(level) + " : l'application devient plus difficile.";
        if (down) return "Tu redescends au niveau " + label(level) + " : l'application redevient plus facile.";
        if (pnl >= 0) return "Partie gagnante : ton portefeuille augmente.";
        return "Partie perdante : regarde ton bilan pour comprendre pourquoi.";
    }

    private void notifyAfterGame(Long userId, boolean up, boolean down, UserLevel level, TraderProfile p) {
        if (ProgressionRules.bankrupt(p.stats())) {
            notifications.notify(userId, tn.esprit.marketsim.notification.Notification.Type.BANKRUPT, "Portefeuille vide",
                    "Ton portefeuille est passé sous 100 $. Recommence avec 1 000 $ au niveau Débutant (clique sur ton niveau en haut à droite).",
                    "/progression");
        } else if (up) {
            notifications.notify(userId, tn.esprit.marketsim.notification.Notification.Type.LEVEL_UP, "Nouveau niveau : " + label(level),
                    "Bravo ! " + ProgressionRules.difficulty(level).summary(), "/progression");
        } else if (down) {
            notifications.notify(userId, tn.esprit.marketsim.notification.Notification.Type.LEVEL_DOWN, "Retour au niveau " + label(level),
                    "Ton portefeuille ou ton score ne remplit plus les conditions du niveau précédent. L'application redevient plus facile.",
                    "/progression");
        } else if (p.getBalance() < 300) {
            notifications.notify(userId, tn.esprit.marketsim.notification.Notification.Type.LOW_BALANCE, "Portefeuille bas",
                    String.format(java.util.Locale.FRANCE, "Il te reste %,.2f $. Achète par petites tranches et respecte le stop-loss.", p.getBalance()),
                    "/progression");
        }
    }

    static String label(UserLevel level) {
        return switch (level) {
            case DEBUTANT -> "Débutant";
            case INTERMEDIAIRE -> "Intermédiaire";
            case AVANCE -> "Avancé";
            case EXPERT -> "Expert";
        };
    }

    private ProgressionResponse toResponse(TraderProfile p) {
        Stats s = p.stats();
        Requirement next = ProgressionRules.nextRequirement(p.getLevel());
        return new ProgressionResponse(
                ProgressionRules.round2(s.balance()), ProgressionRules.START_BALANCE,
                ProgressionRules.round2(Math.max(p.getPeakBalance(), s.balance())),
                p.getLevel(), p.getBestLevel(), ProgressionRules.skillScore(s), s.games(), s.trades(),
                s.winRate(), s.profitFactor(), s.discipline(), s.avgMaxDrawdown(), p.getRestarts(),
                ProgressionRules.bankrupt(s), next == null ? null : next.level(),
                ProgressionRules.goals(s, next), ProgressionRules.difficulty(p.getLevel()), p.getUpdatedAt());
    }
}
