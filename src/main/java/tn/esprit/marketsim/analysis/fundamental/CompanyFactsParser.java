package tn.esprit.marketsim.analysis.fundamental;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.FiscalYear;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.ReportedValue;
import tn.esprit.marketsim.analysis.fundamental.XbrlInstanceParser.InstanceShares;
import tn.esprit.marketsim.analysis.fundamental.XbrlInstanceParser.Total;
import tools.jackson.databind.JsonNode;

/**
 * Module 3 : extrait les donnees annuelles du fichier « companyfacts » de la SEC (fonctions pures, testables
 * sans reseau).
 *
 * <h2>Format du fichier</h2>
 * {@code facts -> us-gaap | dei -> Libelle -> units -> USD | USD/shares | shares -> [faits]}. Un fait porte une
 * valeur ({@code val}), sa periode ({@code start} pour une donnee sur l'exercice, {@code end}), le formulaire qui
 * l'a publie ({@code form} : 10-K, 10-Q...), sa partie d'exercice ({@code fp} : FY, Q1...), le numero de depot
 * ({@code accn}) et la date de depot ({@code filed}).
 *
 * <h2>Regles d'extraction</h2>
 * <ol>
 *   <li>Seuls les faits des rapports annuels comptent : {@code form = "10-K"} et {@code fp = "FY"}. Les rapports
 *       trimestriels (10-Q) et les rectificatifs (10-K/A) sont ignores.</li>
 *   <li>Les exercices sont reperes par leur date de cloture, a partir du chiffre d'affaires et du resultat net
 *       publies sur une annee entiere (entre {@value #MIN_DAYS} et {@value #MAX_DAYS} jours : un 10-K contient aussi
 *       des chiffres trimestriels). Le depot de reference d'un exercice est le premier qui l'a publie.</li>
 *   <li>Un meme exercice est republie dans les 10-K suivants (colonnes de comparaison). Si la valeur a ete
 *       corrigee depuis, on garde celle du depot le plus recent : le BPA d'avant une division d'actions est ainsi
 *       remplace par le BPA retraite.</li>
 *   <li>Pour chaque donnee, les regles de {@link SecConcepts} sont essayees dans l'ordre. Rien ne s'applique :
 *       la donnee reste absente. On n'invente jamais une valeur.</li>
 *   <li>Nombre d'actions : voir {@link #shares}.</li>
 * </ol>
 */
public final class CompanyFactsParser {

    /** Nombre d'exercices gardes. */
    public static final int YEARS = 3;
    /** Bornes d'un exercice complet, en jours (52 ou 53 semaines, ou annee civile). */
    static final int MIN_DAYS = 340;
    static final int MAX_DAYS = 380;
    /**
     * Ecart en dessous duquel deux effectifs a la meme date sont consideres egaux (arrondis au million). Aucune
     * division d'actions n'est aussi proche de 1 (la plus petite courante, 21 pour 20, vaut 1,05).
     */
    static final double SAME_COUNT_TOLERANCE = 0.02;

    private CompanyFactsParser() {
    }

    /** Un fait XBRL utile au calcul. */
    record Fact(LocalDate start, LocalDate end, double value, String accession, int fiscalYear, LocalDate filed) {

        boolean annual() {
            if (start == null) return false;
            long days = ChronoUnit.DAYS.between(start, end);
            return days >= MIN_DAYS && days <= MAX_DAYS;
        }
    }

    public static FundamentalsData parse(JsonNode root) {
        String cik = String.format("%010d", root.path("cik").asLong());
        String name = root.path("entityName").asString("");
        Map<String, List<Fact>> facts = annualFacts(root.path("facts"));

        List<FiscalYear> periods = fiscalYears(facts);
        List<FiscalYear> years = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            FiscalYear period = periods.get(i);
            Map<FundamentalItem, ReportedValue> values = new EnumMap<>(FundamentalItem.class);
            SecConcepts.RULES.forEach((item, rules) -> resolve(rules, facts, period).ifPresent(v -> values.put(item, v)));
            shares(facts, periods, i).ifPresent(v -> values.put(FundamentalItem.SHARES_OUTSTANDING, v));
            years.add(new FiscalYear(period.fiscalYear(), period.start(), period.end(), period.accession(),
                    period.filed(), values));
        }
        return new FundamentalsData(cik, name, years);
    }

    // ===================== Lecture du fichier =====================

    /** Libelles a lire et leur unite : ceux des regles, plus les deux libelles du nombre d'actions. */
    static Map<String, String> wantedConcepts() {
        Map<String, String> out = new LinkedHashMap<>();
        SecConcepts.RULES.forEach((item, rules) -> {
            for (XbrlRule r : rules) {
                r.required().forEach(c -> out.put(c, item.unit().xbrl()));
                r.optional().forEach(c -> out.put(c, item.unit().xbrl()));
            }
        });
        out.put(SecConcepts.COVER_SHARES, FundamentalItem.Unit.SHARES.xbrl());
        out.put(SecConcepts.YEAR_END_SHARES, FundamentalItem.Unit.SHARES.xbrl());
        return out;
    }

    /** Faits 10-K annuels de tous les libelles utiles, dans l'unite attendue pour leur donnee. */
    static Map<String, List<Fact>> annualFacts(JsonNode facts) {
        Map<String, List<Fact>> out = new HashMap<>();
        wantedConcepts().forEach((concept, unit) -> {
            String[] parts = concept.split(":", 2);
            JsonNode list = facts.path(parts[0]).path(parts[1]).path("units").path(unit);
            List<Fact> kept = new ArrayList<>();
            for (JsonNode f : list.values()) {
                if (!"10-K".equals(f.path("form").asString("")) || !"FY".equals(f.path("fp").asString(""))
                        || !f.path("val").isNumber() || f.path("end").asString("").isEmpty()
                        || f.path("filed").asString("").isEmpty()) {
                    continue;
                }
                String start = f.path("start").asString("");
                kept.add(new Fact(start.isEmpty() ? null : LocalDate.parse(start),
                        LocalDate.parse(f.path("end").asString()), f.path("val").asDouble(),
                        f.path("accn").asString(""), f.path("fy").asInt(0),
                        LocalDate.parse(f.path("filed").asString())));
            }
            out.put(concept, kept);
        });
        return out;
    }

    // ===================== Exercices =====================

    /**
     * Les {@value #YEARS} derniers exercices, du plus recent au plus ancien. Pour chacun : le premier depot qui l'a
     * publie (son numero d'exercice, sa date) et ses dates de debut et de fin.
     */
    static List<FiscalYear> fiscalYears(Map<String, List<Fact>> facts) {
        Map<LocalDate, Fact> firstReport = new TreeMap<>(Comparator.reverseOrder());
        for (FundamentalItem item : List.of(FundamentalItem.REVENUE, FundamentalItem.NET_INCOME)) {
            for (XbrlRule rule : SecConcepts.RULES.get(item)) {
                for (String concept : rule.required()) {
                    for (Fact f : facts.getOrDefault(concept, List.of())) {
                        if (!f.annual()) continue;
                        firstReport.merge(f.end(), f, (a, b) -> b.filed().isBefore(a.filed()) ? b : a);
                    }
                }
            }
        }
        return firstReport.values().stream()
                .limit(YEARS)
                .map(f -> new FiscalYear(f.fiscalYear(), f.start(), f.end(), f.accession(), f.filed(), Map.of()))
                .toList();
    }

    // ===================== Valeurs =====================

    /** Premiere regle applicable : somme de ses libelles, avec le nom des libelles utilises. */
    static Optional<ReportedValue> resolve(List<XbrlRule> rules, Map<String, List<Fact>> facts, FiscalYear year) {
        for (XbrlRule rule : rules) {
            double sum = 0;
            List<String> used = new ArrayList<>();
            boolean complete = true;
            for (String concept : rule.required()) {
                Optional<Double> v = valueAt(concept, facts, year);
                if (v.isEmpty()) {
                    complete = false;
                    break;
                }
                sum += v.get();
                used.add(concept);
            }
            if (!complete) continue;
            for (String concept : rule.optional()) {
                Optional<Double> v = valueAt(concept, facts, year);
                if (v.isPresent()) {
                    sum += v.get();
                    used.add(concept);
                }
            }
            return Optional.of(new ReportedValue(sum, String.join(" + ", used)));
        }
        return Optional.empty();
    }

    /**
     * Valeur d'un libelle pour un exercice : le fait date de la cloture (sur l'annee entiere pour une donnee de
     * l'exercice), publie par le depot le plus recent (valeur corrigee s'il y a eu une correction).
     */
    static Optional<Double> valueAt(String concept, Map<String, List<Fact>> facts, FiscalYear year) {
        return facts.getOrDefault(concept, List.of()).stream()
                .filter(f -> f.end().equals(year.end()) && (f.start() == null || f.annual()))
                .max(Comparator.comparing(Fact::filed))
                .map(Fact::value);
    }

    // ===================== Nombre d'actions =====================

    /**
     * Nombre d'actions d'un exercice, comparable d'un exercice a l'autre.
     * <ul>
     *   <li><b>Dernier exercice</b> : page de garde de son 10-K ({@code dei:}), le nombre le plus recent connu (une
     *       date proche du depot). Il sert au ratio cours / valeur comptable.</li>
     *   <li><b>Exercices precedents</b> : la page de garde de leur propre 10-K n'est jamais retraitee (NVIDIA :
     *       2,5 milliards d'actions en fevrier 2024, avant sa division par 10). On prend donc le nombre en circulation
     *       a leur cloture, tel que republie par le 10-K le plus recent qui le donne : il est retraite.</li>
     *   <li>Si aucun 10-K ulterieur ne l'a republie apres une division, il est corrige du facteur constate (voir
     *       {@link #splitFactor}).</li>
     * </ul>
     * Une societe a plusieurs categories d'actions n'a souvent aucun de ces nombres dans companyfacts : ils sont
     * completes ensuite par l'instance XBRL du dernier 10-K ({@link #withInstance}).
     */
    static Optional<ReportedValue> shares(Map<String, List<Fact>> facts, List<FiscalYear> years, int index) {
        FiscalYear year = years.get(index);
        if (index == 0) {
            Optional<Double> cover = facts.getOrDefault(SecConcepts.COVER_SHARES, List.of()).stream()
                    .filter(f -> f.accession().equals(year.accession()))
                    .max(Comparator.comparing(Fact::end))
                    .map(Fact::value);
            if (cover.isPresent()) return Optional.of(new ReportedValue(cover.get(), SecConcepts.COVER_SHARES));
        }
        List<Fact> counts = facts.getOrDefault(SecConcepts.YEAR_END_SHARES, List.of());
        Optional<Fact> latest = counts.stream()
                .filter(f -> f.start() == null && f.end().equals(year.end()))
                .max(Comparator.comparing(Fact::filed));
        if (latest.isEmpty()) return Optional.empty();
        Fact f = latest.get();
        double factor = splitFactor(counts, f.accession(), years.get(0).accession());
        if (factor == 1) return Optional.of(new ReportedValue(f.value(), SecConcepts.YEAR_END_SHARES));
        return Optional.of(new ReportedValue(f.value() * factor,
                SecConcepts.YEAR_END_SHARES + " × " + plain(factor) + " (division d'actions)"));
    }

    /**
     * Facteur de division d'actions entre deux depots. Un nombre d'actions a une date donnee ne change que si les
     * actions ont ete divisees (ou regroupees) entre-temps : on compare un meme effectif publie par les deux depots.
     * Exemple NVIDIA : 2 464 millions au 28/01/2024 dans le 10-K de 2024, 24 643 millions dans celui de 2025 : 10.
     *
     * @return le facteur arrondi a 3 chiffres significatifs, ou 1 (aucune date commune, ou meme effectif)
     */
    static double splitFactor(List<Fact> counts, String fromAccession, String toAccession) {
        if (fromAccession.equals(toAccession)) return 1;
        Map<LocalDate, Double> before = new HashMap<>();
        for (Fact f : counts) {
            if (f.start() == null && f.accession().equals(fromAccession)) before.put(f.end(), f.value());
        }
        for (Fact f : counts) {
            Double old = before.get(f.end());
            if (f.start() != null || !f.accession().equals(toAccession) || old == null || old <= 0) continue;
            double factor = new BigDecimal(f.value() / old).round(new MathContext(3)).doubleValue();
            return Math.abs(factor - 1) < SAME_COUNT_TOLERANCE ? 1 : factor;
        }
        return 1;
    }

    /** 10.0 -> « 10 », 1.5 -> « 1.5 ». */
    private static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    // ===================== Instance XBRL (plusieurs categories d'actions) =====================

    /**
     * Faut-il lire l'instance XBRL du dernier 10-K ? Oui si le dernier exercice n'a pas son nombre d'actions de page
     * de garde, ou si un exercice n'a aucun nombre d'actions : cas des societes a plusieurs categories d'actions.
     */
    public static boolean needsInstance(FundamentalsData data) {
        if (data.years().isEmpty()) return false;
        ReportedValue latest = data.years().get(0).values().get(FundamentalItem.SHARES_OUTSTANDING);
        if (latest == null || !latest.concept().equals(SecConcepts.COVER_SHARES)) return true;
        return data.years().stream().anyMatch(y -> !y.values().containsKey(FundamentalItem.SHARES_OUTSTANDING));
    }

    /**
     * Complete le nombre d'actions avec l'instance XBRL du dernier 10-K : page de garde (toutes categories) pour le
     * dernier exercice, nombre a la cloture pour un exercice qui n'en a pas. Les valeurs de ce depot sont les plus
     * recentes, donc retraitees.
     */
    public static FundamentalsData withInstance(FundamentalsData data, InstanceShares instance) {
        List<FiscalYear> years = new ArrayList<>();
        for (int i = 0; i < data.years().size(); i++) {
            FiscalYear y = data.years().get(i);
            Map<FundamentalItem, ReportedValue> values = new EnumMap<>(FundamentalItem.class);
            values.putAll(y.values());
            ReportedValue current = values.get(FundamentalItem.SHARES_OUTSTANDING);
            boolean coverMissing = current == null || !current.concept().equals(SecConcepts.COVER_SHARES);
            Total yearEnd = instance.yearEnd().get(y.end());
            if (i == 0 && coverMissing && instance.cover() != null) {
                values.put(FundamentalItem.SHARES_OUTSTANDING, new ReportedValue(instance.cover().value(),
                        SecConcepts.COVER_SHARES + instance.cover().labelSuffix()));
            } else if (current == null && yearEnd != null) {
                values.put(FundamentalItem.SHARES_OUTSTANDING, new ReportedValue(yearEnd.value(),
                        SecConcepts.YEAR_END_SHARES + yearEnd.labelSuffix()));
            }
            years.add(new FiscalYear(y.fiscalYear(), y.start(), y.end(), y.accession(), y.filed(), values));
        }
        return new FundamentalsData(data.cik(), data.companyName(), years);
    }
}
