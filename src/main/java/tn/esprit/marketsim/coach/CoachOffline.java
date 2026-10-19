package tn.esprit.marketsim.coach;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import tn.esprit.marketsim.coach.CoachDtos.CoachAnalysis;
import tn.esprit.marketsim.coach.CoachDtos.Indicators;
import tn.esprit.marketsim.coach.CoachDtos.Levels;
import tn.esprit.marketsim.coach.CoachDtos.Reason;

/**
 * Reponses du coach sans cle API : les questions de trading courantes (que faire ? la courbe ? pourquoi ? les
 * risques ? l'historique ?) sont traitees a partir de l'analyse chiffree, donc le coach reste utile hors-ligne.
 * Comprend quelques formulations en arabizi (« chtamel », « nechri », « nbi3 »...).
 */
public final class CoachOffline {

    private static final Pattern ADVICE = Pattern.compile(
            "achat|achet|buy|vend|vente|sell|gard|hold|que faire|quoi faire|maintenant|signal|conseil|dois.?je|"
                    + "chtamel|chnowa|na3mel|nechri|nechry|nbi3|nbay3|9olli|olli");
    private static final Pattern CHART = Pattern.compile("courbe|graphique|tendance|chart|analys|support|resistance|niveau|corba");
    private static final Pattern WHY = Pattern.compile("pourquoi|raison|explique|3lech|alech|leh");
    private static final Pattern RISK = Pattern.compile("risque|stop|objectif|perte|volatil|target|khatar");
    private static final Pattern HISTORY = Pattern.compile("historique|fiable|track|passe|performance|marche vraiment|backtest|reel");

    private CoachOffline() {
    }

    public static Optional<String> answer(String question, CoachAnalysis a) {
        String q = normalize(question);
        boolean wanted = ADVICE.matcher(q).find() || CHART.matcher(q).find() || WHY.matcher(q).find()
                || RISK.matcher(q).find() || HISTORY.matcher(q).find();
        if (!wanted) return Optional.empty();
        if (a == null) {
            return Optional.of("Dis-moi sur quel actif tu veux mon avis : choisis-le dans la liste du coach, puis appuie sur Analyser. "
                    + "Je lirai la vraie courbe et je te dirai quand acheter, garder ou vendre.");
        }
        if (HISTORY.matcher(q).find()) {
            return Optional.of(a.trackRecord() == null
                    ? "Je n'ai pas assez d'historique réel sur " + a.symbol() + " pour te donner un bilan fiable de cette stratégie."
                    : a.trackRecord().text());
        }
        if (RISK.matcher(q).find()) return Optional.of(risk(a));
        if (WHY.matcher(q).find()) return Optional.of(why(a));
        if (CHART.matcher(q).find() && !ADVICE.matcher(q).find()) return Optional.of(chart(a));
        return Optional.of(a.speech());
    }

    static String chart(CoachAnalysis a) {
        Indicators i = a.indicators();
        Levels l = a.levels();
        StringBuilder sb = new StringBuilder(String.format(Locale.FRANCE,
                "Sur %s, la tendance de fond est %s : le cours est à %s, la moyenne 50 jours à %s et la moyenne 200 jours à %s. ",
                a.name(), CoachAnalyzer.trendWord(i.trend()), CoachAnalyzer.px(a.price()), CoachAnalyzer.px(i.sma50()),
                CoachAnalyzer.px(i.sma200())));
        sb.append(String.format(Locale.FRANCE, "Le RSI est à %.0f, %s. ", i.rsi14(),
                i.rsi14() > 70 ? "zone de surachat" : i.rsi14() < 30 ? "zone de survente" : "dans la zone neutre"));
        sb.append("Le support à surveiller est ").append(CoachAnalyzer.px(l.support()));
        sb.append(l.resistance() != null ? ", la résistance " + CoachAnalyzer.px(l.resistance()) + "." : ", et il n'y a pas de résistance proche : le cours est sur ses plus hauts.");
        sb.append(String.format(Locale.FRANCE, " La volatilité récente est de %.0f %% par an, contre %.0f %% d'habitude.",
                i.volatilityAnnual() * 100, i.volatilityNormal() * 100));
        return sb.toString();
    }

    static String why(CoachAnalysis a) {
        StringBuilder sb = new StringBuilder("Voici pourquoi je dis " + CoachAnalyzer.verb(a.action()) + " sur " + a.name() + ". ");
        for (Reason r : a.reasons()) {
            String t = r.text();
            sb.append(Character.toUpperCase(t.charAt(0))).append(t.substring(1)).append(t.endsWith(".") ? " " : ". ");
        }
        return sb.toString().replace("..", ".").trim();
    }

    static String risk(CoachAnalysis a) {
        Levels l = a.levels();
        return String.format(Locale.FRANCE,
                "Pour %s, mon stop est à %s, soit %.1f %% sous le cours, calculé sur la volatilité récente. L'objectif est à %s, soit deux fois le risque. "
                        + "Si le cours clôture sous le stop, la logique du signal est cassée : on sort sans discuter.",
                a.name(), CoachAnalyzer.px(l.stop()), (1 - l.stop() / a.price()) * 100, CoachAnalyzer.px(l.target())).replace("..", ".");
    }

    static String normalize(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
