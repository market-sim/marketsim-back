package tn.esprit.marketsim.analysis.fundamental;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.FiscalYear;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsData.ReportedValue;
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
 *       corrigee depuis, on garde celle du depot le plus recent.</li>
 *   <li>Le nombre d'actions de la page de garde ({@code dei:...}) est date du jour du depot, pas de la cloture :
 *       on prend celui du depot de reference de l'exercice.</li>
 *   <li>Pour chaque donnee, les regles de {@link SecConcepts} sont essayees dans l'ordre. Rien ne s'applique :
 *       la donnee reste absente. On n'invente jamais une valeur.</li>
 * </ol>
 */
public final class CompanyFactsParser {

    /** Nombre d'exercices gardes. */
    public static final int YEARS = 3;
    /** Bornes d'un exercice complet, en jours (52 ou 53 semaines, ou annee civile). */
    static final int MIN_DAYS = 340;
    static final int MAX_DAYS = 380;

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

        List<FiscalYear> years = new ArrayList<>();
        for (FiscalYear period : fiscalYears(facts)) {
            Map<FundamentalItem, ReportedValue> values = new EnumMap<>(FundamentalItem.class);
            for (FundamentalItem item : FundamentalItem.values()) {
                resolve(SecConcepts.RULES.get(item), facts, period).ifPresent(v -> values.put(item, v));
            }
            years.add(new FiscalYear(period.fiscalYear(), period.start(), period.end(), period.accession(),
                    period.filed(), values));
        }
        return new FundamentalsData(cik, name, years);
    }

    // ===================== Lecture du fichier =====================

    /** Faits 10-K annuels de tous les libelles du catalogue, dans l'unite attendue pour leur donnee. */
    static Map<String, List<Fact>> annualFacts(JsonNode facts) {
        Map<String, List<Fact>> out = new HashMap<>();
        for (FundamentalItem item : FundamentalItem.values()) {
            for (String concept : concepts(SecConcepts.RULES.get(item))) {
                String[] parts = concept.split(":", 2);
                JsonNode list = facts.path(parts[0]).path(parts[1]).path("units").path(item.unit().xbrl());
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
            }
        }
        return out;
    }

    private static Set<String> concepts(List<XbrlRule> rules) {
        Set<String> all = new LinkedHashSet<>();
        for (XbrlRule r : rules) {
            all.addAll(r.required());
            all.addAll(r.optional());
        }
        return all;
    }

    // ===================== Exercices =====================

    /**
     * Les {@value #YEARS} derniers exercices, du plus recent au plus ancien. Pour chacun : le premier depot qui l'a
     * publie (son numero d'exercice, sa date) et ses dates de debut et de fin.
     */
    static List<FiscalYear> fiscalYears(Map<String, List<Fact>> facts) {
        Map<LocalDate, Fact> firstReport = new TreeMap<>(Comparator.reverseOrder());
        for (FundamentalItem item : List.of(FundamentalItem.REVENUE, FundamentalItem.NET_INCOME)) {
            for (String concept : concepts(SecConcepts.RULES.get(item))) {
                for (Fact f : facts.getOrDefault(concept, List.of())) {
                    if (!f.annual()) continue;
                    firstReport.merge(f.end(), f, (a, b) -> b.filed().isBefore(a.filed()) ? b : a);
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
     * Valeur d'un libelle pour un exercice.
     * <ul>
     *   <li>page de garde ({@code dei:}) : le fait publie dans le depot de reference de l'exercice ;</li>
     *   <li>sinon : le fait date de la cloture (sur l'annee entiere pour une donnee de l'exercice), publie par
     *       le depot le plus recent (valeur corrigee s'il y a eu une correction).</li>
     * </ul>
     */
    static Optional<Double> valueAt(String concept, Map<String, List<Fact>> facts, FiscalYear year) {
        boolean coverPage = concept.startsWith("dei:");
        return facts.getOrDefault(concept, List.of()).stream()
                .filter(f -> coverPage
                        ? f.accession().equals(year.accession())
                        : f.end().equals(year.end()) && (f.start() == null || f.annual()))
                .max(Comparator.comparing(Fact::filed))
                .map(Fact::value);
    }
}
