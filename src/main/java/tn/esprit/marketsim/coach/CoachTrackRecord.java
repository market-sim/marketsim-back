package tn.esprit.marketsim.coach;

import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.dto.WalkForwardDtos.Performance;
import tn.esprit.marketsim.agent.engine.LinearPolicy;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.agent.realdata.WalkForward;
import tn.esprit.marketsim.agent.realdata.WalkForwardService;
import tn.esprit.marketsim.agent.tournament.TournamentService;
import tn.esprit.marketsim.coach.CoachDtos.TrackRecord;

/**
 * Ce que la strategie du coach a donne sur CET actif, hors echantillon (walk-forward, memes regles que l'onglet
 * « Donnees reelles »). Le coach l'annonce avec son conseil : un signal sans son historique n'a pas de sens.
 */
public final class CoachTrackRecord {

    static final int TRAIN_DAYS = 504;
    static final int TEST_DAYS = 126;
    static final int MIN_WINDOWS = 3;

    private CoachTrackRecord() {
    }

    /** @return null si l'historique est trop court pour au moins 3 fenetres de test */
    public static TrackRecord compute(PriceSeries s, double risk) {
        if (WalkForward.windowCount(s.size(), TRAIN_DAYS, TEST_DAYS) < MIN_WINDOWS) return null;
        WalkForward.Result r = WalkForward.run(s.closes(), s.dates(), TRAIN_DAYS, TEST_DAYS, risk,
                (train, sigma, seed) -> LinearPolicy.of(AgentStrategy.MOMENTUM, risk), 1);
        List<WalkForward.Window> w = r.windows();
        Performance a = WalkForwardService.performance(r.agentCurve());
        Performance b = WalkForwardService.performance(r.benchmarkCurve());
        double[] agent = w.stream().mapToDouble(WalkForward.Window::agentReturn).toArray();
        double[] bench = w.stream().mapToDouble(WalkForward.Window::benchmarkReturn).toArray();
        double[] c = TournamentService.compare(agent, bench, new SplittableRandom(1));
        double meanExcess = Stats.mean(agent) - Stats.mean(bench);
        String from = w.get(0).testFrom().toString();
        String to = w.get(w.size() - 1).testTo().toString();
        return new TrackRecord(w.size(), from, to, a.totalReturn(), b.totalReturn(), a.sharpe(), b.sharpe(), a.maxDrawdown(),
                b.maxDrawdown(), meanExcess, c[4], text(w.size(), from, to, a, b, meanExcess, c[4]));
    }

    /** Version courte, lue a voix haute : l'essentiel du bilan en une phrase. */
    public static String spoken(TrackRecord tr) {
        String verdict = tr.pValue() < 0.05
                ? (tr.meanExcess() > 0 ? "surplus statistiquement significatif" : "significativement en dessous")
                : "écart non significatif";
        return String.format(Locale.FRANCE,
                "Sur l'historique réel hors échantillon, cette stratégie fait %+.0f pour cent contre %+.0f pour cent pour le buy and hold, "
                        + "avec une perte maximale de %.0f contre %.0f pour cent ; %s.",
                tr.strategyReturn() * 100, tr.benchmarkReturn() * 100, Math.abs(tr.strategyMaxDrawdown()) * 100,
                Math.abs(tr.benchmarkMaxDrawdown()) * 100, verdict);
    }

    static String text(int windows, String from, String to, Performance a, Performance b, double meanExcess, double p) {
        StringBuilder sb = new StringBuilder(String.format(Locale.FRANCE,
                "Historique réel de cette stratégie sur cet actif, hors échantillon, %d fenêtres de six mois de %s à %s : %+.0f %% contre %+.0f %% pour le buy and hold",
                windows, from.substring(0, 4), to.substring(0, 4), a.totalReturn() * 100, b.totalReturn() * 100));
        boolean lessRisk = a.maxDrawdown() > b.maxDrawdown() + 0.05;
        boolean betterSharpe = a.sharpe() > b.sharpe() + 0.05;
        if (lessRisk || betterSharpe) {
            sb.append(String.format(Locale.FRANCE, ", mais avec %s", betterSharpe
                    ? String.format(Locale.FRANCE, "un meilleur Sharpe (%.2f contre %.2f)", a.sharpe(), b.sharpe()) : ""));
            if (lessRisk) {
                sb.append(betterSharpe ? " et " : "").append(String.format(Locale.FRANCE,
                        "une perte maximale réduite (%.0f %% contre %.0f %%)", a.maxDrawdown() * 100, b.maxDrawdown() * 100));
            }
        }
        sb.append(". ");
        if (p < 0.05 && meanExcess > 0) {
            sb.append("Le surplus de rendement est statistiquement significatif.");
        } else if (p < 0.05) {
            sb.append("Elle fait significativement moins bien que le buy and hold en rendement brut.");
        } else {
            sb.append("L'écart de rendement avec le buy and hold n'est pas statistiquement significatif.");
        }
        return sb.toString();
    }
}
