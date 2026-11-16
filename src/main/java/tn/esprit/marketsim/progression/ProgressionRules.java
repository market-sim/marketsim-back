package tn.esprit.marketsim.progression;

import java.util.ArrayList;
import java.util.List;

import tn.esprit.marketsim.user.UserLevel;

/**
 * Regles du systeme de progression (logique pure, sans Spring, testable seule).
 *
 * <p>Chaque trader commence DEBUTANT avec {@link #START_BALANCE} dollars virtuels. Son niveau depend a la fois
 * de l'argent qu'il a fait grandir et de la qualite de son trading (taux de reussite, gains contre pertes,
 * discipline sur le stop-loss, controle des baisses). Un trader qui perd de l'argent par rapport au depart
 * reste (ou redescend) DEBUTANT. Sous {@link #BANKRUPT_THRESHOLD} dollars il est ruine et doit recommencer.</p>
 *
 * <p>Plus le niveau est haut, plus l'application est difficile : moins d'aide du coach, frais plus eleves,
 * krach impose et plus violent.</p>
 */
public final class ProgressionRules {

    private ProgressionRules() {
    }

    /**
     * Capital de depart UNIQUE (le meme que le trading du module 2, {@code trading.initial-capital}) : le jeu et le
     * trading partagent un seul portefeuille, {@code users.virtual_balance}.
     */
    public static final double START_BALANCE = 100_000.0;
    /** En dessous (1 % du depart), le portefeuille ne permet plus de jouer : le trader doit recommencer. */
    public static final double BANKRUPT_THRESHOLD = START_BALANCE * 0.01;
    /** Seuil d'alerte « portefeuille bas » (30 % du depart). */
    public static final double LOW_BALANCE_THRESHOLD = START_BALANCE * 0.30;

    /** Statistiques cumulees depuis le dernier depart (ou redemarrage). */
    public record Stats(double balance, int games, int trades, int wins, double grossWin, double grossLoss,
                        int disciplinedTrades, double sumMaxDrawdown) {

        public static Stats fresh() {
            return new Stats(START_BALANCE, 0, 0, 0, 0, 0, 0, 0);
        }

        public double winRate() {
            return trades == 0 ? 0 : (double) wins / trades;
        }

        /** Gains bruts / pertes brutes (plafonne a 5). */
        public double profitFactor() {
            if (grossLoss <= 1e-9) return grossWin > 0 ? 5 : 0;
            return Math.min(5, grossWin / grossLoss);
        }

        /** Part des trades fermes avant que la perte depasse 1,5 fois le stop conseille. */
        public double discipline() {
            return trades == 0 ? 0 : (double) disciplinedTrades / trades;
        }

        /** Baisse maximale moyenne par partie (valeur positive : 0,12 = 12 %). */
        public double avgMaxDrawdown() {
            return games == 0 ? 0 : sumMaxDrawdown / games;
        }

        public double moneyRatio() {
            return balance / START_BALANCE;
        }
    }

    /** Resultat d'une partie, pour la mise a jour des statistiques. */
    public record GameOutcome(double finalEquity, List<Double> tradeReturns, int disciplinedTrades, double maxDrawdown) {
    }

    /** Applique une partie : le portefeuille devient la valeur finale, les statistiques s'accumulent. */
    public static Stats apply(Stats s, GameOutcome g) {
        int wins = 0;
        double gw = 0;
        double gl = 0;
        for (double r : g.tradeReturns()) {
            if (r > 0) {
                wins++;
                gw += r;
            } else {
                gl += -r;
            }
        }
        return new Stats(round2(Math.max(0, g.finalEquity())), s.games() + 1, s.trades() + g.tradeReturns().size(),
                s.wins() + wins, s.grossWin() + gw, s.grossLoss() + gl, s.disciplinedTrades() + g.disciplinedTrades(),
                s.sumMaxDrawdown() + Math.abs(g.maxDrawdown()));
    }

    /** En dessous de ce nombre de trades, le score est reduit : un trade chanceux ne prouve pas une competence. */
    public static final int SKILL_FULL_TRADES = 10;

    /**
     * Score de competence 0-100 : reussite 30 %, gains/pertes 25 %, discipline 25 %, controle des baisses 20 %,
     * multiplie par min(1, trades / 10) pour ne pas recompenser quelques trades heureux.
     */
    public static int skillScore(Stats s) {
        if (s.trades() == 0) return 0;
        double evidence = Math.min(1.0, (double) s.trades() / SKILL_FULL_TRADES);
        double win = s.winRate();
        double pf = Math.min(1, s.profitFactor() / 2.5);
        double disc = s.discipline();
        double dd = 1 - Math.min(1, s.avgMaxDrawdown() / 0.30);
        return (int) Math.round(100 * evidence * (0.30 * win + 0.25 * pf + 0.25 * disc + 0.20 * dd));
    }

    /** Conditions pour atteindre un niveau (DEBUTANT n'en a pas). */
    public record Requirement(UserLevel level, int minGames, int minTrades, double minMoneyRatio, int minSkill) {
    }

    public static final List<Requirement> REQUIREMENTS = List.of(
            new Requirement(UserLevel.INTERMEDIAIRE, 3, 5, 1.20, 45),
            new Requirement(UserLevel.AVANCE, 8, 15, 1.80, 60),
            new Requirement(UserLevel.EXPERT, 12, 30, 3.00, 75));

    public static boolean meets(Stats s, Requirement r) {
        return s.games() >= r.minGames() && s.trades() >= r.minTrades()
                && s.moneyRatio() >= r.minMoneyRatio() && skillScore(s) >= r.minSkill();
    }

    /** Le plus haut niveau dont toutes les conditions sont remplies ; DEBUTANT des que l'on perd de l'argent. */
    public static UserLevel levelFor(Stats s) {
        if (s.moneyRatio() < 1.0) return UserLevel.DEBUTANT;
        UserLevel level = UserLevel.DEBUTANT;
        for (Requirement r : REQUIREMENTS) {
            if (meets(s, r)) level = r.level();
            else break;
        }
        return level;
    }

    public static Requirement nextRequirement(UserLevel current) {
        for (Requirement r : REQUIREMENTS) {
            if (r.level().ordinal() > current.ordinal()) return r;
        }
        return null;
    }

    /** Une condition du prochain niveau, avec la valeur actuelle (pour la barre de progression). */
    public record Goal(String key, String label, double current, double target, boolean done) {
    }

    public static List<Goal> goals(Stats s, Requirement r) {
        List<Goal> out = new ArrayList<>();
        if (r == null) return out;
        out.add(new Goal("games", "Parties jouées", s.games(), r.minGames(), s.games() >= r.minGames()));
        out.add(new Goal("trades", "Trades fermés", s.trades(), r.minTrades(), s.trades() >= r.minTrades()));
        double target = round2(START_BALANCE * r.minMoneyRatio());
        out.add(new Goal("balance", "Portefeuille ($)", round2(s.balance()), target, s.balance() >= target - 1e-9));
        int skill = skillScore(s);
        out.add(new Goal("skill", "Score de compétence", skill, r.minSkill(), skill >= r.minSkill()));
        return out;
    }

    public static boolean bankrupt(Stats s) {
        return s.balance() < BANKRUPT_THRESHOLD;
    }

    // ------------------------------------------------------------------------------------------------
    // Difficulte de l'application selon le niveau
    // ------------------------------------------------------------------------------------------------

    /** Quelle aide donne le guide. */
    public enum GuideMode { DETAILED, TIPS, NONE }

    /** Quand le conseil du coach est visible. */
    public enum CoachMode { ALWAYS, ALWAYS_NO_HIGHLIGHT, AFTER_DECISION, HIDDEN }

    /**
     * @param feeRate        frais par ordre (fraction du montant)
     * @param crashForced    krach surprise toujours present
     * @param crashPct       taille du krach (en %)
     * @param minDays        duree minimale d'une partie
     * @param maxStepDays    nombre maximal de jours d'avance par decision
     * @param allowSkipToEnd bouton « passer au bilan »
     * @param showStopHint   affichage du stop-loss conseille
     */
    public record Difficulty(String label, int stars, GuideMode guide, CoachMode coach, double feeRate,
                             boolean crashForced, double crashPct, int minDays, int maxStepDays,
                             boolean allowSkipToEnd, boolean showStopHint, String summary) {
    }

    public static Difficulty difficulty(UserLevel level) {
        return switch (level == null ? UserLevel.DEBUTANT : level) {
            case DEBUTANT -> new Difficulty("Facile", 1, GuideMode.DETAILED, CoachMode.ALWAYS, 0.0005,
                    false, 20, 63, 5, true, true,
                    "Guide pas à pas, le coach te montre le meilleur choix, frais réduits, krach au choix.");
            case INTERMEDIAIRE -> new Difficulty("Normal", 2, GuideMode.TIPS, CoachMode.ALWAYS_NO_HIGHLIGHT, 0.0010,
                    false, 20, 63, 5, true, true,
                    "Le coach donne son avis sans le souligner, quelques astuces, frais normaux.");
            case AVANCE -> new Difficulty("Difficile", 3, GuideMode.NONE, CoachMode.AFTER_DECISION, 0.0020,
                    true, 25, 126, 3, false, false,
                    "Plus de guide, le conseil du coach n'apparaît qu'après ta décision, krach imposé, frais doublés.");
            case EXPERT -> new Difficulty("Pro", 4, GuideMode.NONE, CoachMode.HIDDEN, 0.0030,
                    true, 30, 126, 1, false, false,
                    "Aucune aide : pas de coach, krach violent imposé, frais élevés, un jour à la fois.");
        };
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
