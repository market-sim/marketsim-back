package tn.esprit.marketsim.agent.anomaly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.engine.Stats;

/**
 * Detection d'anomalies comportementales d'un agent : compare ses decisions recentes a sa
 * reference "normale" avec des tests statistiques classiques.
 *
 * <ul>
 *   <li>Confiance : z-score de la moyenne recente (chute = perte de reperes, hausse = exces de confiance) ;</li>
 *   <li>Repartition BUY / SELL / HOLD : test EXACT sous la loi predictive Dirichlet-multinomiale
 *       (avec 8 decisions le khi-deux n'est pas valide, et les proportions de reference sont elles-memes
 *       estimees : la loi predictive tient compte des deux incertitudes) ;</li>
 *   <li>Taille des positions : z-score sur le logarithme du montant engage ;</li>
 *   <li>Derniere decision isolee : a plus de 3 ecarts-types de la reference ;</li>
 *   <li>Approbation humaine : test de deux proportions (unilateral).</li>
 * </ul>
 * Classe pure (sans base de donnees) : entierement testable.
 */
public final class AnomalyDetector {

    public static final int RECENT_WINDOW = 8;
    public static final int MIN_RECENT = 5;
    public static final int MIN_HISTORY_BASELINE = 15;

    /** Seuil d'alerte par test : 2,5 % / 5 tests (correction de Bonferroni). */
    public static final double ALERT_P = 0.025 / 5;

    private AnomalyDetector() {
    }

    /** Une decision vue par le detecteur. notional = montant engage (0 pour HOLD), rating = note 1-5 ou null. */
    public record Observation(AgentAction action, double confidence, double notional, Integer rating) {
    }

    public enum Severity { LOW, MEDIUM, HIGH }

    public enum Type { CONFIDENCE_DROP, OVERCONFIDENCE, ACTION_MIX, POSITION_SIZE, OUTLIER_DECISION, APPROVAL_DROP }

    /**
     * @param baseline valeur de reference, recent = valeur sur les dernieres decisions
     * @param score    statistique du test (z ou X2)
     * @param pValue   p-value du test quand elle existe, sinon null
     */
    public record Anomaly(Type type, Severity severity, String title, String message, String metric,
                          double baseline, double recent, double score, Double pValue) {
    }

    public static List<Anomaly> detect(List<Observation> baseline, List<Observation> recent) {
        List<Anomaly> out = new ArrayList<>();
        if (baseline.size() < 10 || recent.size() < MIN_RECENT) return out;

        confidence(baseline, recent, out);
        actionMix(baseline, recent, out);
        positionSize(baseline, recent, out);
        outlier(baseline, recent, out);
        approval(baseline, recent, out);

        out.sort(Comparator.comparing(Anomaly::severity).reversed());
        return out;
    }

    // ----- 1. Confiance -----

    /**
     * Test STRATIFIE par type d'action : la confiance d'un HOLD et celle d'un BUY ne se calculent pas pareil
     * (~85 % contre ~65 %). Comparer des moyennes globales confondrait "moins de HOLD" avec "moins confiant".
     * Chaque decision recente est donc comparee aux decisions de reference du meme type :
     * r = (c - moyenne_a) / (ecart-type_a * sqrt(1 + 1/n_a)), puis z = somme(r) / sqrt(m).
     */
    static void confidence(List<Observation> baseline, List<Observation> recent, List<Anomaly> out) {
        double sumResidual = 0;
        double sumObserved = 0;
        double sumExpected = 0;
        int m = 0;
        for (Observation o : recent) {
            double[] same = baseline.stream().filter(b -> b.action() == o.action()).mapToDouble(Observation::confidence).toArray();
            if (same.length < 4) continue;
            double mean = Stats.mean(same);
            double sd = Math.max(0.02, Stats.std(same)) * Math.sqrt(1 + 1.0 / same.length);
            sumResidual += (o.confidence() - mean) / sd;
            sumObserved += o.confidence();
            sumExpected += mean;
            m++;
        }
        if (m < 4) return;
        double z = sumResidual / Math.sqrt(m);
        double mb = sumExpected / m;
        double mr = sumObserved / m;
        double diff = mr - mb;
        if (z <= -2.5 && diff <= -0.05) {
            Severity sev = z <= -5 ? Severity.HIGH : z <= -3 ? Severity.MEDIUM : Severity.LOW;
            out.add(new Anomaly(Type.CONFIDENCE_DROP, sev, "Chute de confiance",
                    String.format(Locale.FRANCE, "À type de décision égal, la confiance moyenne est passée de %.0f %% à %.0f %% sur les %d dernières décisions : "
                            + "l'agent ne reconnaît plus les situations de marché qu'il connaît.", mb * 100, mr * 100, m),
                    "Confiance moyenne", mb, mr, z, twoSidedNormalP(z)));
        } else if (z >= 3.5 && diff >= 0.08) {
            out.add(new Anomaly(Type.OVERCONFIDENCE, z >= 5 ? Severity.MEDIUM : Severity.LOW, "Excès de confiance",
                    String.format(Locale.FRANCE, "La confiance moyenne bondit de %.0f %% à %.0f %% : l'agent est inhabituellement sûr de lui, "
                            + "ce qui précède souvent des prises de risque excessives.", mb * 100, mr * 100),
                    "Confiance moyenne", mb, mr, z, twoSidedNormalP(z)));
        }
    }

    // ----- 2. Repartition des actions (khi-deux) -----

    static void actionMix(List<Observation> baseline, List<Observation> recent, List<Anomaly> out) {
        AgentAction[] actions = AgentAction.values();
        double[] pb = new double[actions.length];
        int[] or = new int[actions.length];
        double[] alpha = new double[actions.length];
        for (int k = 0; k < actions.length; k++) {
            AgentAction a = actions[k];
            long cb = baseline.stream().filter(o -> o.action() == a).count();
            alpha[k] = cb + 1.0; // a posteriori Dirichlet(1 + effectifs de reference)
            pb[k] = alpha[k] / (baseline.size() + actions.length);
            or[k] = (int) recent.stream().filter(o -> o.action() == a).count();
        }
        double x2 = 0;
        int worst = 0;
        double worstGap = -1;
        for (int k = 0; k < actions.length; k++) {
            double e = recent.size() * pb[k];
            x2 += (or[k] - e) * (or[k] - e) / e;
            double gap = Math.abs((double) or[k] / recent.size() - pb[k]);
            if (gap > worstGap) {
                worstGap = gap;
                worst = k;
            }
        }
        double p = exactPredictiveP(or, alpha);
        if (p < 0.05 && worstGap >= 0.2) {
            // seuils corriges (Bonferroni) : 5 tests tournent en parallele, on vise moins de 2 % de fausses alertes au total
            Severity sev = p < ALERT_P / 10 ? Severity.HIGH : p < ALERT_P ? Severity.MEDIUM : Severity.LOW;
            double before = pb[worst];
            double after = (double) or[worst] / recent.size();
            out.add(new Anomaly(Type.ACTION_MIX, sev, "Changement de comportement",
                    String.format(Locale.FRANCE, "La part de décisions %s est passée de %.0f %% à %.0f %%. "
                            + "Un tel changement n'arrive par hasard qu'avec une probabilité de %s (test exact).",
                            label(actions[worst]), before * 100, after * 100, formatP(p)),
                    "Part de " + actions[worst], before, after, x2, p));
        }
    }

    // ----- 3. Taille des positions -----

    static void positionSize(List<Observation> baseline, List<Observation> recent, List<Anomaly> out) {
        double[] b = baseline.stream().filter(o -> o.notional() > 0).mapToDouble(o -> Math.log(o.notional())).toArray();
        double[] r = recent.stream().filter(o -> o.notional() > 0).mapToDouble(o -> Math.log(o.notional())).toArray();
        if (b.length < 5 || r.length < 3) return;
        double sd = Math.max(0.05, Stats.std(b));
        double z = (Stats.mean(r) - Stats.mean(b)) / (sd / Math.sqrt(r.length));
        double before = Math.exp(Stats.mean(b));
        double after = Math.exp(Stats.mean(r));
        if (z >= 3 && after / before >= 1.25) {
            out.add(new Anomaly(Type.POSITION_SIZE, z >= 5 ? Severity.HIGH : Severity.MEDIUM, "Positions anormalement grosses",
                    String.format(Locale.FRANCE, "Le montant engagé par ordre passe d'environ %,.0f $ à %,.0f $ : l'agent prend nettement plus de risque que d'habitude.",
                            before, after),
                    "Montant moyen par ordre ($)", before, after, z, twoSidedNormalP(z)));
        } else if (z <= -3 && after / before <= 0.75) {
            out.add(new Anomaly(Type.POSITION_SIZE, z <= -6 ? Severity.MEDIUM : Severity.LOW, "Positions anormalement petites",
                    String.format(Locale.FRANCE, "Le montant engagé par ordre tombe d'environ %,.0f $ à %,.0f $ : l'agent est devenu très frileux.",
                            before, after),
                    "Montant moyen par ordre ($)", before, after, z, twoSidedNormalP(z)));
        }
    }

    // ----- 4. Derniere decision atypique -----

    static void outlier(List<Observation> baseline, List<Observation> recent, List<Anomaly> out) {
        double[] b = baseline.stream().mapToDouble(Observation::confidence).toArray();
        double sd = Math.max(0.02, Stats.std(b));
        Observation last = recent.get(recent.size() - 1);
        double z = (last.confidence() - Stats.mean(b)) / sd;
        if (Math.abs(z) >= 3) {
            out.add(new Anomaly(Type.OUTLIER_DECISION, Severity.LOW, "Décision isolée atypique",
                    String.format(Locale.FRANCE, "La dernière décision (%s, confiance %.0f %%) est à %.1f écarts-types de ce que fait habituellement l'agent.",
                            last.action(), last.confidence() * 100, Math.abs(z)),
                    "Confiance de la dernière décision", Stats.mean(b), last.confidence(), z, twoSidedNormalP(z)));
        }
    }

    // ----- 5. Approbation humaine (test de deux proportions, unilateral) -----

    static void approval(List<Observation> baseline, List<Observation> recent, List<Anomaly> out) {
        List<Observation> rb = baseline.stream().filter(o -> o.rating() != null).toList();
        List<Observation> rr = recent.stream().filter(o -> o.rating() != null).toList();
        if (rb.size() < 5 || rr.size() < 3) return;
        double p1 = rb.stream().filter(o -> o.rating() >= 4).count() / (double) rb.size();
        double p2 = rr.stream().filter(o -> o.rating() >= 4).count() / (double) rr.size();
        double pooled = (p1 * rb.size() + p2 * rr.size()) / (rb.size() + rr.size());
        double se = Math.sqrt(Math.max(1e-9, pooled * (1 - pooled) * (1.0 / rb.size() + 1.0 / rr.size())));
        double z = (p2 - p1) / se;
        double p = Stats.normalCdf(z); // H1 : l'approbation a baisse
        if (p < 0.05 && p1 - p2 >= 0.25) {
            out.add(new Anomaly(Type.APPROVAL_DROP, p < 0.01 ? Severity.HIGH : Severity.MEDIUM, "Les utilisateurs désapprouvent",
                    String.format(Locale.FRANCE, "Le taux d'approbation humaine chute de %.0f %% à %.0f %% : les décisions récentes sont jugées mauvaises.",
                            p1 * 100, p2 * 100),
                    "Taux d'approbation", p1, p2, z, p));
        }
    }

    // ----- Outils -----

    /**
     * Test multinomial exact : probabilite, sous la repartition de reference, d'observer une repartition
     * aussi improbable (ou plus) que celle observee. Somme sur tous les resultats possibles.
     */
    static double exactMultinomialP(int[] observed, double[] probs) {
        int n = 0;
        for (int o : observed) n += o;
        double pObserved = multinomial(observed, probs);
        double p = 0;
        for (int a = 0; a <= n; a++) {
            for (int b = 0; a + b <= n; b++) {
                int[] counts = {a, b, n - a - b};
                double q = multinomial(counts, probs);
                if (q <= pObserved * (1 + 1e-9)) p += q;
            }
        }
        return Math.min(1, p);
    }

    /**
     * Test exact sous la loi predictive Dirichlet-multinomiale : integre l'incertitude sur les proportions
     * de reference (estimees sur un echantillon fini) en plus du hasard des decisions recentes.
     */
    static double exactPredictiveP(int[] observed, double[] alpha) {
        int n = 0;
        for (int o : observed) n += o;
        double pObserved = dirichletMultinomial(observed, alpha);
        double p = 0;
        for (int a = 0; a <= n; a++) {
            for (int b = 0; a + b <= n; b++) {
                double q = dirichletMultinomial(new int[] {a, b, n - a - b}, alpha);
                if (q <= pObserved * (1 + 1e-9)) p += q;
            }
        }
        return Math.min(1, p);
    }

    private static double dirichletMultinomial(int[] counts, double[] alpha) {
        int n = 0;
        double sumAlpha = 0;
        double logP = 0;
        for (int k = 0; k < counts.length; k++) {
            n += counts[k];
            sumAlpha += alpha[k];
            logP += Stats.logGamma(counts[k] + alpha[k]) - Stats.logGamma(alpha[k]) - Stats.logFactorial(counts[k]);
        }
        return Math.exp(logP + Stats.logFactorial(n) + Stats.logGamma(sumAlpha) - Stats.logGamma(n + sumAlpha));
    }

    private static double multinomial(int[] counts, double[] probs) {
        int n = 0;
        double logP = 0;
        for (int k = 0; k < counts.length; k++) {
            n += counts[k];
            logP += counts[k] * Math.log(probs[k]) - Stats.logFactorial(counts[k]);
        }
        return Math.exp(logP + Stats.logFactorial(n));
    }

    static double twoSidedNormalP(double z) {
        return Math.min(1, 2 * (1 - Stats.normalCdf(Math.abs(z))));
    }

    static String formatP(double p) {
        return p < 0.0001 ? "moins de 1 sur 10 000" : String.format(Locale.FRANCE, "%.2f %%", p * 100);
    }

    private static String label(AgentAction a) {
        return switch (a) {
            case BUY -> "d'achat";
            case SELL -> "de vente";
            case HOLD -> "d'attente";
        };
    }
}
