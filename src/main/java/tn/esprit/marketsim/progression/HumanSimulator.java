package tn.esprit.marketsim.progression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rejoue cote serveur les ordres du joueur sur les prix d'une partie (meme logique que {@code simulateHuman}
 * du frontend) : le resultat qui change le portefeuille est calcule ici, pas envoye par le navigateur.
 *
 * <p>Un achat engage {@code fraction} des liquidites, une vente liquide toute la position, ordres executes a la
 * cloture du jour, frais {@code feeRate} sur chaque ordre.</p>
 */
public final class HumanSimulator {

    private HumanSimulator() {
    }

    public enum Side { BUY, SELL }

    public record Order(int day, Side side, Double fraction) {
    }

    /**
     * @param tradeReturns      rendement de chaque aller-retour (la position encore ouverte est vendue a la fin)
     * @param disciplinedTrades allers-retours dont la perte n'a pas depasse 1,5 fois le stop conseille
     */
    public record Result(double finalEquity, List<Double> tradeReturns, int disciplinedTrades, double maxDrawdown,
                         double fees, int orders) {
    }

    public static Result run(double[] prices, double capital, List<Order> orders, double feeRate, double stopPct) {
        int last = prices.length - 1;
        List<Order> sorted = orders.stream()
                .filter(o -> o != null && o.side() != null && o.day() >= 0 && o.day() <= last)
                .sorted(Comparator.comparingInt(Order::day))
                .toList();
        double cash = capital;
        double units = 0;
        double basis = 0;
        double fees = 0;
        double peak = capital;
        double worst = 0;
        double worstSinceEntry = 0;
        int executed = 0;
        int disciplined = 0;
        List<Double> returns = new ArrayList<>();
        int k = 0;

        for (int d = 0; d <= last; d++) {
            double price = prices[d];
            while (k < sorted.size() && sorted.get(k).day() == d) {
                Order o = sorted.get(k++);
                if (o.side() == Side.BUY) {
                    double f = o.fraction() == null ? 1 : Math.max(0, Math.min(1, o.fraction()));
                    double spend = cash * f;
                    if (spend > 1e-9) {
                        double bought = spend * (1 - feeRate) / price;
                        if (units == 0) worstSinceEntry = 0;
                        units += bought;
                        basis += bought * price;
                        cash -= spend;
                        fees += spend * feeRate;
                        executed++;
                    }
                } else if (units > 0) {
                    double proceeds = units * price;
                    double fee = proceeds * feeRate;
                    double avgEntry = basis / units;
                    double ret = price / avgEntry - 1;
                    returns.add(ret);
                    if (Math.min(ret, worstSinceEntry) >= -1.5 * stopPct) disciplined++;
                    cash += proceeds - fee;
                    fees += fee;
                    units = 0;
                    basis = 0;
                    executed++;
                }
            }
            if (units > 0) {
                worstSinceEntry = Math.min(worstSinceEntry, price / (basis / units) - 1);
            }
            double equity = cash + units * price;
            peak = Math.max(peak, equity);
            worst = Math.min(worst, equity / peak - 1);
        }

        double finalEquity = cash;
        if (units > 0) {
            double price = prices[last];
            double proceeds = units * price;
            double ret = price / (basis / units) - 1;
            returns.add(ret);
            if (Math.min(ret, worstSinceEntry) >= -1.5 * stopPct) disciplined++;
            // valorisee au dernier cours, sans frais de sortie : meme valeur que celle affichee au joueur
            finalEquity += proceeds;
        }
        return new Result(finalEquity, returns, disciplined, worst, fees, executed);
    }
}
