package tn.esprit.marketsim.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Module 5 : moteur de prevision local (Java), meme contrat JSON que le service Python d'ensemble
 * ({@code /internal/v1/forecast}). Il sert quand ce service n'est pas lance : les predictions marchent toujours,
 * sur les vraies donnees historiques.
 * <p>
 * Quatre candidats sont evalues en walk-forward (prevision a un pas sur les dernieres seances, MAE / RMSE), et le
 * meilleur (plus petite MAE) produit la prevision a {@code horizon} jours :
 * <ul>
 *   <li><b>naive</b> : demain = aujourd'hui ;</li>
 *   <li><b>drift</b> : marche aleatoire avec la tendance moyenne ;</li>
 *   <li><b>holt</b> : lissage exponentiel double de Holt (niveau + tendance, alpha / beta par grille) ;</li>
 *   <li><b>arima</b> : ARIMA(1,1,0) — AR(1) sur les variations, estime par moindres carres.</li>
 * </ul>
 * Le sentiment (score -1..+1 des actualites) ajoute une petite inflexion de tendance : 0,1 ecart-type journalier
 * par jour au maximum, renvoyee dans {@code sentimentContribution} (en % du prix final).
 */
@Component
public class LocalForecastEngine {

    public static final String VERSION = "ensemble-v1";
    static final double SENTIMENT_WEIGHT = 0.1;

    private final ObjectMapper json = new ObjectMapper();

    /** Un modele : prevision de {@code h} pas a partir d'une serie. */
    interface Model {
        double[] forecast(double[] y, int h);
    }

    record Candidate(String name, double mae, double rmse, double[] residuals) {
    }

    public ObjectNode forecast(List<Double> prices, int horizon, double sentimentScore) {
        double[] y = prices.stream().mapToDouble(Double::doubleValue).toArray();
        int n = y.length;
        ObjectNode out = json.createObjectNode();
        if (n < 20 || horizon < 1) {
            out.put("status", "INSUFFICIENT_DATA");
            return out;
        }
        String[] names = {"naive", "drift", "holt", "arima"};
        Model[] models = {LocalForecastEngine::naive, LocalForecastEngine::drift, LocalForecastEngine::holt,
                LocalForecastEngine::arima110};
        int validation = Math.max(5, Math.min(30, n / 4));

        List<Candidate> candidates = new ArrayList<>();
        for (int m = 0; m < models.length; m++) {
            double[] res = new double[validation];
            double abs = 0;
            double sq = 0;
            for (int k = 0; k < validation; k++) {
                int t = n - validation + k;          // on prevoit y[t] avec y[0..t-1]
                double[] train = java.util.Arrays.copyOf(y, t);
                double pred = models[m].forecast(train, 1)[0];
                double e = y[t] - pred;
                res[k] = e;
                abs += Math.abs(e);
                sq += e * e;
            }
            candidates.add(new Candidate(names[m], abs / validation, Math.sqrt(sq / validation), res));
        }
        Candidate best = candidates.stream().min(Comparator.comparingDouble(Candidate::mae)).orElseThrow();
        Model selected = models[java.util.Arrays.asList(names).indexOf(best.name())];
        double[] path = selected.forecast(y, horizon);

        // inflexion de sentiment, bornee
        double s = Double.isFinite(sentimentScore) ? Math.max(-1, Math.min(1, sentimentScore)) : 0;
        double sigma = dailyStd(y);
        double last = y[n - 1];
        double before = path[horizon - 1];
        for (int k = 0; k < horizon; k++) {
            path[k] = Math.max(1e-6, path[k] + s * SENTIMENT_WEIGHT * sigma * (k + 1));
        }

        out.put("status", "ANALYZED");
        out.put("selectedModel", best.name());
        out.put("modelVersion", VERSION);
        out.put("engine", "java-local");
        ArrayNode f = out.putArray("forecast");
        for (int k = 0; k < horizon; k++) {
            ObjectNode p = f.addObject();
            p.put("day", k + 1);
            p.put("value", round6(path[k]));
        }
        ObjectNode metrics = out.putObject("metrics");
        metrics.put("mae", round6(best.mae()));
        metrics.put("rmse", round6(best.rmse()));
        ArrayNode r = out.putArray("residuals");
        for (double e : best.residuals()) r.add(round6(e));
        ArrayNode c = out.putArray("candidates");
        for (Candidate cand : candidates) {
            ObjectNode o = c.addObject();
            o.put("model", cand.name());
            o.put("mae", round6(cand.mae()));
            o.put("rmse", round6(cand.rmse()));
            o.put("selected", cand == best);
        }
        out.put("sentimentScore", s);
        out.put("sentimentContribution", last > 0 ? round6((path[horizon - 1] - before) / last * 100) : 0);
        return out;
    }

    // ===================== Modeles =====================

    static double[] naive(double[] y, int h) {
        double[] out = new double[h];
        java.util.Arrays.fill(out, y[y.length - 1]);
        return out;
    }

    static double[] drift(double[] y, int h) {
        int n = y.length;
        double slope = n > 1 ? (y[n - 1] - y[0]) / (n - 1) : 0;
        double[] out = new double[h];
        for (int k = 0; k < h; k++) out[k] = y[n - 1] + slope * (k + 1);
        return out;
    }

    /** Holt lineaire : alpha et beta choisis sur une grille par erreur a un pas dans l'echantillon. */
    static double[] holt(double[] y, int h) {
        double bestSse = Double.MAX_VALUE;
        double bestL = y[y.length - 1];
        double bestB = 0;
        for (double alpha = 0.1; alpha <= 0.91; alpha += 0.2) {
            for (double beta = 0.05; beta <= 0.51; beta += 0.15) {
                double level = y[0];
                double trend = y.length > 1 ? y[1] - y[0] : 0;
                double sse = 0;
                for (int t = 1; t < y.length; t++) {
                    double pred = level + trend;
                    double e = y[t] - pred;
                    sse += e * e;
                    double newLevel = alpha * y[t] + (1 - alpha) * (level + trend);
                    trend = beta * (newLevel - level) + (1 - beta) * trend;
                    level = newLevel;
                }
                if (sse < bestSse) {
                    bestSse = sse;
                    bestL = level;
                    bestB = trend;
                }
            }
        }
        double[] out = new double[h];
        for (int k = 0; k < h; k++) out[k] = bestL + bestB * (k + 1);
        return out;
    }

    /** ARIMA(1,1,0) : d_t = c + phi·d_{t-1} + e (phi borne a ]-1, 1[ pour rester stable). */
    static double[] arima110(double[] y, int h) {
        int n = y.length;
        if (n < 4) return naive(y, h);
        double[] d = new double[n - 1];
        for (int i = 1; i < n; i++) d[i - 1] = y[i] - y[i - 1];
        double mx = 0;
        double my = 0;
        int m = d.length - 1;
        for (int i = 0; i < m; i++) {
            mx += d[i];
            my += d[i + 1];
        }
        mx /= m;
        my /= m;
        double cov = 0;
        double var = 0;
        for (int i = 0; i < m; i++) {
            cov += (d[i] - mx) * (d[i + 1] - my);
            var += (d[i] - mx) * (d[i] - mx);
        }
        double phi = var > 0 ? Math.max(-0.95, Math.min(0.95, cov / var)) : 0;
        double c = my - phi * mx;
        double[] out = new double[h];
        double prevD = d[d.length - 1];
        double level = y[n - 1];
        for (int k = 0; k < h; k++) {
            prevD = c + phi * prevD;
            level += prevD;
            out[k] = level;
        }
        return out;
    }

    static double dailyStd(double[] y) {
        int n = y.length;
        if (n < 3) return 0;
        double mean = 0;
        for (int i = 1; i < n; i++) mean += y[i] - y[i - 1];
        mean /= (n - 1);
        double v = 0;
        for (int i = 1; i < n; i++) v += Math.pow(y[i] - y[i - 1] - mean, 2);
        return Math.sqrt(v / (n - 2));
    }

    private static double round6(double v) {
        return Double.isFinite(v) ? Double.parseDouble(String.format(Locale.ROOT, "%.6f", v)) : 0;
    }
}
