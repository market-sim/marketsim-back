package tn.esprit.marketsim.analysis;

import java.time.LocalDate;
import java.util.Locale;

import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.common.Hashing;

/**
 * Module 3 : prix utilises par tous les calculs du module (indicateurs, backtest, comparaison). Fonctions pures.
 *
 * <p>Pourquoi normaliser : juste apres un telechargement Yahoo, le module 6 renvoie des clotures non arrondies,
 * alors que son cache (et l'empreinte {@link PriceSeries#hash()}) les garde a 6 decimales. Sans normalisation,
 * le premier calcul apres l'expiration du cache pouvait differer tres legerement des suivants, avec la meme
 * empreinte. On relit donc toujours la serie comme le cache la relirait : memes clotures, meme empreinte,
 * memes resultats. Le module 6 ({@code MarketDataService}) n'est pas modifie.
 */
public final class Prices {

    /** Nombre de decimales gardees pour une cloture (celui du cache du module 6). */
    public static final int DECIMALS = 6;

    private Prices() {
    }

    /**
     * Serie aux clotures arrondies a {@value #DECIMALS} decimales, exactement comme le cache du module 6 :
     * on passe par son propre encodage ({@link PriceSeries#encode()}, puis {@link PriceSeries#decode}).
     * Une serie deja arrondie ressort identique, empreinte comprise.
     */
    public static PriceSeries normalized(PriceSeries series) {
        return PriceSeries.decode(series.symbol(), series.source(), series.encode());
    }

    /**
     * Empreinte SHA-256 des seances comprises entre {@code from} et {@code to} (incluses) : une ligne
     * {@code date,cloture} par seance, cloture a 6 decimales (meme format que le cache du module 6).
     * Elle ne depend que de la periode : la serie peut s'allonger chaque jour sans la changer.
     */
    public static String periodHash(PriceSeries series, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder();
        LocalDate[] dates = series.dates();
        double[] closes = series.closes();
        for (int i = 0; i < dates.length; i++) {
            if (dates[i].isBefore(from) || dates[i].isAfter(to)) continue;
            sb.append(dates[i]).append(',').append(String.format(Locale.ROOT, "%.6f", closes[i])).append('\n');
        }
        return Hashing.sha256Hex(sb.toString());
    }
}
