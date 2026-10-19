package tn.esprit.marketsim.coach;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.LinearPolicy;
import tn.esprit.marketsim.agent.engine.MarketFeatures;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.TradingPolicy.Contribution;
import tn.esprit.marketsim.agent.engine.TradingPolicy.PolicyDecision;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.agent.realdata.WalkForward;
import tn.esprit.marketsim.coach.CoachDtos.ChartPoint;
import tn.esprit.marketsim.coach.CoachDtos.CoachAnalysis;
import tn.esprit.marketsim.coach.CoachDtos.Indicators;
import tn.esprit.marketsim.coach.CoachDtos.Levels;
import tn.esprit.marketsim.coach.CoachDtos.Reason;
import tn.esprit.marketsim.coach.CoachDtos.Rule;
import tn.esprit.marketsim.coach.CoachDtos.TrackRecord;

/**
 * Analyse d'une courbe REELLE, sans IA generative et sans hasard : memes entrees = meme reponse.
 *
 * <p>Le signal vient du modele lineaire de momentum du projet (valeurs de Shapley exactes, donc chaque conseil est
 * explicable), avec deux garde-fous classiques : on n'achete pas contre une tendance de fond baissiere
 * (cours sous la MM50 elle-meme sous la MM200) ni un actif en surachat extreme (RSI &gt; 78). Les niveaux
 * (support, resistance, stop, objectif) et le plan « quand acheter / garder / vendre » en decoulent.
 * Le coach ne parle donc que de chiffres qu'il a calcules.
 */
public final class CoachAnalyzer {

    public static final int CHART_BARS = 140;
    public static final String DISCLAIMER = "Simulateur pédagogique : ce n'est pas un conseil d'investissement personnalisé.";
    static final double OVERBOUGHT_BLOCK = 78;

    private CoachAnalyzer() {
    }

    public static CoachAnalysis analyze(PriceSeries series, String name, double risk, TrackRecord trackRecord) {
        double[] p = series.closes();
        int n = p.length;
        int t = n - 1;
        if (n < 200) throw new IllegalArgumentException("Historique trop court pour analyser (200 jours minimum)");

        double price = p[t];
        double normalSigma = WalkForward.annualVolatility(p);
        MarketFeatures f = MarketFeatures.compute(p, t, normalSigma);
        PolicyDecision decision = LinearPolicy.of(AgentStrategy.MOMENTUM, risk).decide(f, 0);

        double sma20 = sma(p, t, 20);
        double sma50 = sma(p, t, 50);
        double sma200 = sma(p, t, 200);
        double rsi = Math.max(0, Math.min(100, 50 + 50 * f.rsi()));   // l'arrondi flottant peut depasser 100 de 1e-14
        double realizedVol = realizedVolatility(p, t, 20);
        String trend = price > sma50 && sma50 > sma200 ? "HAUSSIERE" : price < sma50 && sma50 < sma200 ? "BAISSIERE" : "MIXTE";

        // ----- Signal et garde-fous -----
        AgentAction action = decision.action();
        double confidence = decision.confidence();
        List<Reason> reasons = new ArrayList<>();
        if (action == AgentAction.BUY && trend.equals("BAISSIERE")) {
            action = AgentAction.HOLD;
            confidence = Math.max(0.5, confidence * 0.8);
            reasons.add(new Reason("Filtre de tendance", 0, "Le modèle voudrait acheter, mais la tendance de fond est baissière "
                    + "(cours sous la moyenne 50 jours, elle-même sous la moyenne 200 jours) : je ne rentre pas contre elle."));
        } else if (action == AgentAction.BUY && rsi > OVERBOUGHT_BLOCK) {
            action = AgentAction.HOLD;
            confidence = Math.max(0.5, confidence * 0.8);
            reasons.add(new Reason("Filtre de surachat", 0, String.format(Locale.FRANCE,
                    "Le RSI est à %.0f : l'actif est très suracheté, mieux vaut attendre un repli pour entrer.", rsi)));
        }
        List<Contribution> factors = new ArrayList<>(decision.contributions());
        factors.sort(Comparator.comparingDouble((Contribution c) -> -Math.abs(c.contribution())));
        for (Contribution c : factors.stream().limit(3).toList()) {
            reasons.add(new Reason(c.label(), c.contribution(), describe(c.feature(), c.value())));
        }

        // ----- Niveaux -----
        double dailySigma = realizedVol / Math.sqrt(PathGenerator.TRADING_DAYS);
        double stopPct = Backtester.stopDistance(risk, dailySigma);
        double stop = price * (1 - stopPct);
        double target = price * (1 + 2 * stopPct);
        double support = support(p, t, price, sma20, sma50, sma200, stop);
        Double resistance = resistance(p, t, price, sma20, sma50, sma200);
        Levels levels = new Levels(support, resistance, stop, target);

        List<Rule> plan = plan(action, price, levels, sma50);
        double change1d = p[t] / p[t - 1] - 1;
        double change5d = p[t] / p[t - 5] - 1;
        double change20d = p[t] / p[t - 20] - 1;
        Indicators indicators = new Indicators(rsi, sma20, sma50, sma200, realizedVol, normalSigma, f.momentum(), trend);

        String headline = String.format(Locale.FRANCE, "%s : signal %s (confiance du modèle %.0f %%) · tendance %s · RSI %.0f",
                series.symbol(), verb(action).toUpperCase(Locale.FRANCE), confidence * 100, trendWord(trend), rsi);
        String speech = speech(name, price, change1d, trend, action, confidence, reasons, plan, trackRecord);

        return new CoachAnalysis(series.symbol(), name, series.source(), series.dates()[t], price, change1d, change5d,
                change20d, action, confidence, decision.score(), indicators, levels, reasons, plan, trackRecord,
                chart(series), headline, speech, DISCLAIMER, series.hash(), (int) Math.round(risk * 100), null);
    }

    // ===================== Niveaux =====================

    /** Plus haut niveau SOUS le cours parmi : plus bas 20 j, plus bas 60 j, moyennes 20 / 50 / 200 jours. */
    static double support(double[] p, int t, double price, double sma20, double sma50, double sma200, double stop) {
        double best = Stream.of(min(p, t, 20), min(p, t, 60), sma20, sma50, sma200)
                .filter(v -> v < price * 0.999).mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        return Double.isNaN(best) ? stop : best;   // plus bas recents : pas de support visible, on retombe sur le stop
    }

    /** Plus bas niveau AU-DESSUS du cours parmi : plus hauts 20 / 60 j, moyennes ; nul si le cours est sur ses sommets. */
    static Double resistance(double[] p, int t, double price, double sma20, double sma50, double sma200) {
        return Stream.of(max(p, t, 20), max(p, t, 60), sma20, sma50, sma200)
                .filter(v -> v > price * 1.001).mapToDouble(Double::doubleValue).min().stream().boxed().findFirst().orElse(null);
    }

    // ===================== Plan : quand acheter, garder, vendre =====================

    static List<Rule> plan(AgentAction action, double price, Levels l, double sma50) {
        String stop = px(l.stop());
        Rule buy = action == AgentAction.BUY
                ? new Rule(AgentAction.BUY, "Acheter", "Entrée possible maintenant autour de " + px(price) + ", stop à " + stop
                        + ", objectif " + px(l.target()) + " (gain visé = 2 fois le risque).", price, true)
                : new Rule(AgentAction.BUY, "Acheter", l.resistance() != null
                        ? "Si le cours clôture au-dessus de " + px(l.resistance()) + " (cassure) avec un RSI sous 70, "
                                + "ou sur un repli propre vers " + px(l.support()) + " tant que la tendance de fond n'est pas baissière."
                        : "Sur un repli vers " + px(l.support()) + " : le cours est sur ses plus hauts, je ne cours pas après.",
                        l.resistance() != null ? l.resistance() : l.support(), false);
        Rule hold = new Rule(AgentAction.HOLD, "Garder", l.resistance() != null
                ? "Tant que le cours reste entre " + px(l.support()) + " et " + px(l.resistance())
                        + " : ne change rien, garde ta position ou reste à l'écart si tu n'es pas investi."
                : "Tant que le cours reste au-dessus de " + px(l.support()) + " : garde ta position.",
                null, action == AgentAction.HOLD);
        Rule sell = action == AgentAction.SELL
                ? new Rule(AgentAction.SELL, "Vendre", "Signal de sortie : réduis ou ferme la position. Ne rentre à nouveau que si le cours repasse au-dessus de "
                        + px(sma50) + " (moyenne 50 jours).", sma50, true)
                : new Rule(AgentAction.SELL, "Vendre", "Si le cours clôture sous " + stop + " (stop fondé sur la volatilité), "
                        + "ou si le RSI dépasse 75 puis retombe sous 70 (essoufflement de la hausse).", l.stop(), false);
        return List.of(buy, hold, sell);
    }

    // ===================== Texte parle =====================

    static String speech(String name, double price, double change1d, String trend, AgentAction action, double confidence,
                         List<Reason> reasons, List<Rule> plan, TrackRecord tr) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.FRANCE, "%s. Le cours est à %s, %s %.1f pour cent sur la dernière séance. ",
                name, px(price), change1d >= 0 ? "en hausse de" : "en baisse de", Math.abs(change1d) * 100));
        sb.append("La tendance de fond est ").append(trendWord(trend)).append(". ");
        sb.append(String.format(Locale.FRANCE, "Mon signal : %s, avec un indice de confiance de %.0f pour cent. ", verb(action), confidence * 100));
        for (Reason r : reasons.stream().limit(2).toList()) sb.append(capitalize(r.text())).append(". ");
        Rule active = plan.stream().filter(Rule::active).findFirst().orElse(plan.get(1));
        sb.append(active.condition()).append(' ');
        Rule sell = plan.get(2);
        if (!sell.active() && active.action() != AgentAction.BUY) sb.append("Je sors ").append(lowerFirst(sell.condition())).append(' ');
        if (tr != null) sb.append(CoachTrackRecord.spoken(tr)).append(' ');
        sb.append("Rappel : c'est un exercice sur simulateur, pas un conseil d'investissement.");
        return sb.toString().replace("..", ".").trim();
    }

    // ===================== Utilitaires =====================

    static String verb(AgentAction a) {
        return switch (a) {
            case BUY -> "acheter";
            case HOLD -> "garder";
            case SELL -> "vendre";
        };
    }

    static String trendWord(String trend) {
        return switch (trend) {
            case "HAUSSIERE" -> "haussière";
            case "BAISSIERE" -> "baissière";
            default -> "mixte, sans direction nette";
        };
    }

    public static String describe(String feature, double v) {
        return switch (feature) {
            case "momentum" -> v >= 0 ? "le prix monte depuis 20 jours" : "le prix baisse depuis 20 jours";
            case "trend" -> v >= 0 ? "la tendance courte est haussière (moyenne 10 jours au-dessus de la moyenne 30 jours)"
                    : "la tendance courte est baissière (moyenne 10 jours sous la moyenne 30 jours)";
            case "rsi" -> v > 0.3 ? "le RSI signale un surachat" : v < -0.3 ? "le RSI signale une survente"
                    : v >= 0 ? "le RSI est plutôt élevé" : "le RSI est plutôt bas";
            case "meanReversion" -> v >= 0 ? "le prix est bas par rapport à sa moyenne récente" : "le prix est haut par rapport à sa moyenne récente";
            case "volatility" -> v >= 0 ? "le marché est plus agité que d'habitude" : "le marché est plus calme que d'habitude";
            default -> feature;
        };
    }

    /** 12345.6 -> « 12 346 » ; 192.45 -> « 192,45 » ; 1.1790 -> « 1,1790 ». */
    static String px(double v) {
        return String.format(Locale.FRANCE, v < 10 ? "%.4f" : v < 1000 ? "%.2f" : "%.0f", v);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static List<ChartPoint> chart(PriceSeries s) {
        int n = s.size();
        List<ChartPoint> out = new ArrayList<>();
        for (int i = Math.max(49, n - CHART_BARS); i < n; i++) {
            out.add(new ChartPoint(s.dates()[i], round(s.closes()[i]), round(sma(s.closes(), i, 20)), round(sma(s.closes(), i, 50))));
        }
        return out;
    }

    static double round(double v) {
        return v >= 1000 ? Math.round(v * 10) / 10.0 : Math.round(v * 10_000) / 10_000.0;
    }

    static double sma(double[] p, int t, int n) {
        double s = 0;
        for (int i = t - n + 1; i <= t; i++) s += p[i];
        return s / n;
    }

    static double min(double[] p, int t, int n) {
        double m = Double.MAX_VALUE;
        for (int i = t - n + 1; i <= t; i++) m = Math.min(m, p[i]);
        return m;
    }

    static double max(double[] p, int t, int n) {
        double m = -Double.MAX_VALUE;
        for (int i = t - n + 1; i <= t; i++) m = Math.max(m, p[i]);
        return m;
    }

    /** Volatilite annualisee des n derniers rendements logarithmiques. */
    static double realizedVolatility(double[] p, int t, int n) {
        double mean = 0;
        for (int i = t - n + 1; i <= t; i++) mean += Math.log(p[i] / p[i - 1]);
        mean /= n;
        double s = 0;
        for (int i = t - n + 1; i <= t; i++) {
            double r = Math.log(p[i] / p[i - 1]) - mean;
            s += r * r;
        }
        return Math.sqrt(s / (n - 1)) * Math.sqrt(PathGenerator.TRADING_DAYS);
    }
}
