package tn.esprit.marketsim.agent.tournament;

import java.util.SplittableRandom;

/** Donne acces aux methodes package-private de TournamentService depuis les tests du moteur. */
public final class TournamentServiceTestAccess {

    private TournamentServiceTestAccess() {
    }

    public static double[] compare(double[] x, double[] y, SplittableRandom rng) {
        return TournamentService.compare(x, y, rng);
    }
}
