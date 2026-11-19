package tn.esprit.marketsim.analysis.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.admin.AdminRows.BacktestItem;
import tn.esprit.marketsim.analysis.admin.AdminRows.BacktestView;
import tn.esprit.marketsim.analysis.admin.AdminRows.ComparisonItem;
import tn.esprit.marketsim.analysis.admin.AdminRows.ComparisonView;
import tn.esprit.marketsim.analysis.admin.AdminRows.ResultPage;
import tn.esprit.marketsim.analysis.admin.AdminRows.UserActivity;
import tn.esprit.marketsim.analysis.admin.AdminStats.ComparisonFact;
import tn.esprit.marketsim.analysis.admin.AdminStats.Stats;
import tn.esprit.marketsim.analysis.backtest.BacktestService;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.StrategySnapshot;
import tn.esprit.marketsim.analysis.comparison.SavedComparisonService;
import tn.esprit.marketsim.common.NotFoundException;

/**
 * Module 3, administration : resultats de tous les utilisateurs (backtests et comparaisons enregistres) et
 * statistiques. Les routes qui l'appellent sont reservees a l'ADMIN ({@link AnalysisAdminController}).
 * La lecture et la suppression reutilisent les services du module (meme format, meme mise a jour du resume
 * des strategies).
 */
@Service
@RequiredArgsConstructor
public class AnalysisAdminService {

    static final int MAX_PAGE_SIZE = 200;
    static final int MIN_WEEKS = 4;
    static final int MAX_WEEKS = 52;

    private final AdminBacktestQueries backtestQueries;
    private final AdminComparisonQueries comparisonQueries;
    private final BacktestService backtests;
    private final SavedComparisonService comparisons;

    // ===================== Backtests =====================

    /** Backtests de tous les utilisateurs, filtres facultatifs, les plus recents d'abord. */
    @Transactional(readOnly = true)
    public ResultPage<BacktestItem> backtests(Long userId, String symbol, String type, int page, int size) {
        checkPage(page, size);
        var rows = backtestQueries.page(userId, blankToNull(symbol), type(type), PageRequest.of(page, size));
        return new ResultPage<>(rows.getContent().stream().map(BacktestItem::of).toList(), rows.getTotalElements(), page, size);
    }

    @Transactional(readOnly = true)
    public BacktestView backtest(Long id) {
        AdminBacktestRow row = backtestQueries.row(id).orElseThrow(() -> new NotFoundException("Backtest introuvable"));
        return new BacktestView(row.owner(), backtests.findAny(id));
    }

    @Transactional
    public void deleteBacktest(Long id) {
        backtests.deleteAny(id);
    }

    // ===================== Comparaisons =====================

    /** Comparaisons de tous les utilisateurs ; le type filtre celles qui contiennent une strategie de ce type. */
    @Transactional(readOnly = true)
    public ResultPage<ComparisonItem> comparisons(Long userId, String symbol, String type, int page, int size) {
        checkPage(page, size);
        StrategyType wanted = type(type);
        List<ComparisonItem> all = comparisonQueries.rows(userId, blankToNull(symbol)).stream()
                .map(r -> new ComparisonItem(r.id(), r.owner(), r.name(), r.symbol(), r.from(), r.to(), r.createdAt(),
                        r.rankBy(), r.buyHoldReturn(), comparisons.snapshots(r.strategiesJson())))
                .filter(c -> wanted == null || c.strategies().stream().anyMatch(s -> s.type() == wanted))
                .toList();
        int fromIndex = Math.min(page * size, all.size());
        return new ResultPage<>(all.subList(fromIndex, Math.min(fromIndex + size, all.size())), all.size(), page, size);
    }

    @Transactional(readOnly = true)
    public ComparisonView comparison(Long id) {
        AdminComparisonRow row = comparisonQueries.row(id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Comparaison introuvable"));
        return new ComparisonView(row.owner(), comparisons.findAny(id));
    }

    @Transactional
    public void deleteComparison(Long id) {
        comparisons.deleteAny(id);
    }

    // ===================== Utilisateurs et statistiques =====================

    /** Utilisateurs ayant au moins un resultat, du plus au moins actif (filtre de la liste, performances). */
    @Transactional(readOnly = true)
    public List<UserActivity> users() {
        return AdminStats.users(backtestQueries.all(), comparisonFacts());
    }

    @Transactional(readOnly = true)
    public Stats stats(Integer weeks) {
        int w = weeks == null ? 12 : weeks;
        if (w < MIN_WEEKS || w > MAX_WEEKS) {
            throw new IllegalArgumentException("weeks : entre " + MIN_WEEKS + " et " + MAX_WEEKS + " semaines");
        }
        return AdminStats.compute(backtestQueries.all(), comparisonFacts(), LocalDate.now(), w);
    }

    private List<ComparisonFact> comparisonFacts() {
        return comparisonQueries.rows(null, null).stream()
                .map(r -> new ComparisonFact(r.owner(), r.symbol(), r.createdAt(),
                        comparisons.snapshots(r.strategiesJson()).stream().map(StrategySnapshot::type).toList()))
                .toList();
    }

    // ===================== Outils =====================

    private static void checkPage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page : 0 ou plus");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size : entre 1 et " + MAX_PAGE_SIZE);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Type de strategie facultatif ; un type inconnu est une erreur de saisie (400). */
    static StrategyType type(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return StrategyType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type : type de stratégie inconnu « " + raw.strip() + " »");
        }
    }
}
