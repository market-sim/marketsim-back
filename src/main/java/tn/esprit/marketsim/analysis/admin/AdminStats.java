package tn.esprit.marketsim.analysis.admin;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.admin.AdminRows.Owner;
import tn.esprit.marketsim.analysis.admin.AdminRows.UserActivity;

/**
 * Module 3, administration : statistiques sur les backtests et les comparaisons enregistres. Fonctions pures
 * (sans base ni Spring) : testees sur un jeu de donnees connu.
 *
 * <ul>
 *   <li>activite par semaine (du lundi au dimanche) sur les {@code weeks} dernieres semaines ;</li>
 *   <li>actifs les plus testes et types de strategie les plus utilises : chaque backtest compte une fois, chaque
 *       strategie d'une comparaison aussi ;</li>
 *   <li>Sharpe moyen par type : sur les backtests seulement (une comparaison rejoue les memes strategies) ;</li>
 *   <li>utilisateurs les plus actifs ;</li>
 *   <li>meilleures strategies : pour chaque strategie, son meilleur Sharpe sur un backtest d'au moins
 *       {@value #MIN_DAYS_FOR_BEST} jours, puis les {@value #TOP} premieres.</li>
 * </ul>
 */
public final class AdminStats {

    /** Longueur des classements. */
    static final int TOP = 10;
    /** Duree minimale (jours calendaires) d'un backtest retenu pour le classement des meilleures strategies. */
    static final int MIN_DAYS_FOR_BEST = 365;

    private AdminStats() {
    }

    /** Une comparaison enregistree : qui, quel actif, quand, et le type de chaque strategie comparee. */
    public record ComparisonFact(Owner owner, String symbol, LocalDateTime createdAt, List<StrategyType> types) {
    }

    public record Totals(int backtests, int comparisons, int users) {
    }

    /** Une semaine, du lundi {@code start} au dimanche suivant. */
    public record Week(LocalDate start, int backtests, int comparisons) {
    }

    public record AssetUsage(String symbol, int backtests, int comparisons, int total) {
    }

    /** @param averageSharpe moyenne des Sharpe des backtests de ce type (null s'il n'y en a aucun) */
    public record TypeUsage(StrategyType type, int backtests, int inComparisons, int total, Double averageSharpe) {
    }

    /** Meilleur backtest (d'au moins un an) d'une strategie. */
    public record BestStrategy(Long backtestId, Long strategyId, String strategyName, StrategyType strategyType,
                               Owner owner, String symbol, LocalDate from, LocalDate to, double sharpe,
                               Double annualizedReturn, Double maxDrawdown) {
    }

    public record Stats(Totals totals, int weeks, List<Week> weekly, List<AssetUsage> assets, List<TypeUsage> types,
                        List<UserActivity> users, List<BestStrategy> best) {
    }

    public static Stats compute(List<AdminBacktestRow> backtests, List<ComparisonFact> comparisons, LocalDate today,
                                int weeks) {
        List<UserActivity> users = users(backtests, comparisons);
        return new Stats(new Totals(backtests.size(), comparisons.size(), users.size()), weeks,
                weekly(backtests, comparisons, today, weeks), assets(backtests, comparisons), types(backtests, comparisons),
                users.stream().limit(TOP).toList(), best(backtests));
    }

    /** Lundi de la semaine d'une date. */
    static LocalDate monday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Les {@code weeks} dernieres semaines, de la plus ancienne a la semaine en cours ; une semaine vide vaut 0. */
    static List<Week> weekly(List<AdminBacktestRow> backtests, List<ComparisonFact> comparisons, LocalDate today,
                             int weeks) {
        LocalDate current = monday(today);
        LocalDate first = current.minusWeeks(weeks - 1L);
        Map<LocalDate, int[]> counts = new LinkedHashMap<>();
        for (int i = 0; i < weeks; i++) counts.put(first.plusWeeks(i), new int[2]);
        for (AdminBacktestRow b : backtests) {
            int[] c = counts.get(monday(b.createdAt().toLocalDate()));
            if (c != null) c[0]++;
        }
        for (ComparisonFact f : comparisons) {
            int[] c = counts.get(monday(f.createdAt().toLocalDate()));
            if (c != null) c[1]++;
        }
        return counts.entrySet().stream().map(e -> new Week(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }

    /** Les actifs les plus testes (backtests et comparaisons), du plus au moins utilise. */
    static List<AssetUsage> assets(List<AdminBacktestRow> backtests, List<ComparisonFact> comparisons) {
        Map<String, int[]> counts = new HashMap<>();
        for (AdminBacktestRow b : backtests) counts.computeIfAbsent(b.symbol(), k -> new int[2])[0]++;
        for (ComparisonFact f : comparisons) counts.computeIfAbsent(f.symbol(), k -> new int[2])[1]++;
        return counts.entrySet().stream()
                .map(e -> new AssetUsage(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[0] + e.getValue()[1]))
                .sorted(Comparator.comparingInt(AssetUsage::total).reversed().thenComparing(AssetUsage::symbol))
                .limit(TOP).toList();
    }

    /** Tous les types de strategie (meme inutilises), du plus au moins utilise, avec le Sharpe moyen des backtests. */
    static List<TypeUsage> types(List<AdminBacktestRow> backtests, List<ComparisonFact> comparisons) {
        Map<StrategyType, int[]> counts = new EnumMap<>(StrategyType.class);
        Map<StrategyType, double[]> sharpe = new EnumMap<>(StrategyType.class);   // somme, nombre
        for (StrategyType t : StrategyType.values()) {
            counts.put(t, new int[2]);
            sharpe.put(t, new double[2]);
        }
        for (AdminBacktestRow b : backtests) {
            if (b.strategyType() == null) continue;
            counts.get(b.strategyType())[0]++;
            if (b.sharpe() != null) {
                sharpe.get(b.strategyType())[0] += b.sharpe();
                sharpe.get(b.strategyType())[1]++;
            }
        }
        for (ComparisonFact f : comparisons) {
            for (StrategyType t : f.types()) if (t != null) counts.get(t)[1]++;
        }
        List<TypeUsage> out = new ArrayList<>();
        for (StrategyType t : StrategyType.values()) {
            int[] c = counts.get(t);
            double[] s = sharpe.get(t);
            out.add(new TypeUsage(t, c[0], c[1], c[0] + c[1], s[1] == 0 ? null : round4(s[0] / s[1])));
        }
        out.sort(Comparator.comparingInt(TypeUsage::total).reversed());   // tri stable : ordre de l'enum a egalite
        return out;
    }

    /** Activite de chaque utilisateur ayant au moins un resultat, du plus au moins actif. */
    static List<UserActivity> users(List<AdminBacktestRow> backtests, List<ComparisonFact> comparisons) {
        record Acc(Owner owner, int[] counts, double[] sharpe, LocalDateTime[] last) {
        }
        Map<Long, Acc> byUser = new LinkedHashMap<>();
        for (AdminBacktestRow b : backtests) {
            Acc a = byUser.computeIfAbsent(b.ownerId(), k -> new Acc(b.owner(), new int[2], new double[2], new LocalDateTime[1]));
            a.counts()[0]++;
            if (b.sharpe() != null) {
                a.sharpe()[0] += b.sharpe();
                a.sharpe()[1]++;
            }
            a.last()[0] = latest(a.last()[0], b.createdAt());
        }
        for (ComparisonFact f : comparisons) {
            Acc a = byUser.computeIfAbsent(f.owner().id(), k -> new Acc(f.owner(), new int[2], new double[2], new LocalDateTime[1]));
            a.counts()[1]++;
            a.last()[0] = latest(a.last()[0], f.createdAt());
        }
        return byUser.values().stream()
                .map(a -> new UserActivity(a.owner(), a.counts()[0], a.counts()[1],
                        a.sharpe()[1] == 0 ? null : round4(a.sharpe()[0] / a.sharpe()[1]), a.last()[0]))
                .sorted(Comparator.comparingInt((UserActivity u) -> u.backtests() + u.comparisons()).reversed()
                        .thenComparing(UserActivity::lastActivity, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** Pour chaque strategie, son meilleur backtest d'au moins un an ; puis les meilleures par Sharpe. */
    static List<BestStrategy> best(List<AdminBacktestRow> backtests) {
        Map<Long, AdminBacktestRow> bestByStrategy = new HashMap<>();
        for (AdminBacktestRow b : backtests) {
            if (b.sharpe() == null || b.strategyId() == null) continue;
            if (ChronoUnit.DAYS.between(b.from(), b.to()) < MIN_DAYS_FOR_BEST) continue;
            AdminBacktestRow current = bestByStrategy.get(b.strategyId());
            if (current == null || b.sharpe() > current.sharpe()
                    || (Objects.equals(b.sharpe(), current.sharpe()) && b.createdAt().isAfter(current.createdAt()))) {
                bestByStrategy.put(b.strategyId(), b);
            }
        }
        return bestByStrategy.values().stream()
                .sorted(Comparator.comparingDouble(AdminBacktestRow::sharpe).reversed().thenComparing(AdminBacktestRow::id))
                .limit(TOP)
                .map(b -> new BestStrategy(b.id(), b.strategyId(), b.strategyName(), b.strategyType(), b.owner(), b.symbol(),
                        b.from(), b.to(), b.sharpe(), b.annualizedReturn(), b.maxDrawdown()))
                .toList();
    }

    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }
}
