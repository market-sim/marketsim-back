package tn.esprit.marketsim.analysis.backtest;

import java.time.LocalDate;

import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.Prices;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.DataCheckResult;

/**
 * Module 3 : les cours utilises par un backtest ou une comparaison enregistres sont-ils encore ceux d'aujourd'hui ?
 * Fonction pure.
 *
 * <p>On compare l'empreinte des seances de la periode testee seulement ({@link Prices#periodHash}) : la serie
 * s'allonge chaque jour, une empreinte de la serie entiere changerait donc toujours. Une difference sur la meme
 * periode vient d'un ajustement des cours (dividendes, divisions d'actions) ou d'une correction de la source.
 *
 * <p>Limite : les indicateurs utilisent aussi l'historique d'avant la periode (chauffe, surtout pour les EMA).
 * Un changement a cet endroit n'est pas detecte.
 */
public final class DataCheck {

    /** Resultat de la verification. */
    public enum Status {
        /** Memes cours sur la periode : relancer donnerait le meme resultat. */
        SAME,
        /** Les cours de la periode ont change depuis l'enregistrement (ajustements, corrections). */
        CHANGED,
        /** L'historique actuel ne couvre plus toute la periode (la fenetre de 10 ans de la source avance). */
        NOT_COVERED,
        /** Ancien enregistrement sans empreinte de periode : verification impossible. */
        UNVERIFIABLE,
        /** Les cours actuels n'ont pas pu etre charges. */
        UNAVAILABLE
    }

    private DataCheck() {
    }

    /**
     * @param savedPeriodHash empreinte enregistree (null pour un ancien enregistrement)
     * @param from            premiere seance de la periode enregistree
     * @param to              derniere seance de la periode enregistree
     * @param current         cours actuels, deja normalises ({@link Prices#normalized}) ; null si indisponibles
     */
    public static DataCheckResult check(String savedPeriodHash, LocalDate from, LocalDate to, PriceSeries current) {
        if (savedPeriodHash == null) {
            return new DataCheckResult(Status.UNVERIFIABLE, from, to, null, null);
        }
        if (current == null || current.size() == 0) {
            return new DataCheckResult(Status.UNAVAILABLE, from, to, null, null);
        }
        LocalDate first = current.dates()[0];
        LocalDate last = current.dates()[current.size() - 1];
        if (first.isAfter(from) || last.isBefore(to)) {
            return new DataCheckResult(Status.NOT_COVERED, from, to, first, last);
        }
        Status status = savedPeriodHash.equals(Prices.periodHash(current, from, to)) ? Status.SAME : Status.CHANGED;
        return new DataCheckResult(status, from, to, first, last);
    }
}
