package tn.esprit.marketsim.analysis.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.admin.AdminRows.BacktestItem;
import tn.esprit.marketsim.analysis.admin.AdminRows.BacktestView;
import tn.esprit.marketsim.analysis.admin.AdminRows.ComparisonItem;
import tn.esprit.marketsim.analysis.admin.AdminRows.ComparisonView;
import tn.esprit.marketsim.analysis.admin.AdminRows.ResultPage;
import tn.esprit.marketsim.analysis.admin.AdminRows.UserActivity;
import tn.esprit.marketsim.analysis.admin.AdminStats.Stats;
import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService;
import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService.ChangeView;
import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService.DefaultsRequest;
import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService.DefaultsView;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsService;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsService.CacheEntry;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : administration (cahier des charges, section 5 : parametres et resultats des simulations,
 * utilisateurs et leurs performances). Toutes les routes sont reservees a l'ADMIN, par le meme moyen que le
 * reste du projet ({@code @PreAuthorize}, active par {@code @EnableMethodSecurity}) ; un utilisateur ordinaire
 * recoit 403. Le rafraichissement d'une societe en cache reste {@code POST /api/analysis/fundamentals/{symbol}/refresh}.
 *
 * <pre>
 *   GET    /api/analysis/admin/defaults                  parametres par defaut, valeurs d'origine, derniere modification
 *   PUT    /api/analysis/admin/defaults                  les modifie (trace de chaque champ modifie)
 *   GET    /api/analysis/admin/defaults/history          traces des modifications
 *   GET    /api/analysis/admin/users                     utilisateurs ayant des resultats, avec leur activite
 *   GET    /api/analysis/admin/backtests                 ?userId=&symbol=&type=&page=&size=
 *   GET    /api/analysis/admin/backtests/{id}            detail en lecture seule
 *   DELETE /api/analysis/admin/backtests/{id}
 *   GET    /api/analysis/admin/comparisons               ?userId=&symbol=&type=&page=&size=
 *   GET    /api/analysis/admin/comparisons/{id}
 *   DELETE /api/analysis/admin/comparisons/{id}
 *   GET    /api/analysis/admin/stats?weeks=12            statistiques
 *   GET    /api/analysis/admin/fundamentals              societes en cache (analyse fondamentale)
 *   DELETE /api/analysis/admin/fundamentals/{symbol}     supprime une entree du cache
 * </pre>
 */
@RestController
@RequestMapping("/api/analysis/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AnalysisAdminController {

    private final AnalysisAdminService service;
    private final SimulationDefaultsService defaults;
    private final FundamentalsService fundamentals;

    // ----- parametres par defaut -----

    @GetMapping("/defaults")
    public DefaultsView defaults() {
        return defaults.view();
    }

    @PutMapping("/defaults")
    public DefaultsView updateDefaults(@RequestBody DefaultsRequest request, @AuthenticationPrincipal User admin) {
        return defaults.update(request, admin);
    }

    @GetMapping("/defaults/history")
    public List<ChangeView> defaultsHistory() {
        return defaults.history();
    }

    // ----- resultats -----

    @GetMapping("/users")
    public List<UserActivity> users() {
        return service.users();
    }

    @GetMapping("/backtests")
    public ResultPage<BacktestItem> backtests(@RequestParam(required = false) Long userId,
                                              @RequestParam(required = false) String symbol,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.backtests(userId, symbol, type, page, size);
    }

    @GetMapping("/backtests/{id}")
    public BacktestView backtest(@PathVariable Long id) {
        return service.backtest(id);
    }

    @DeleteMapping("/backtests/{id}")
    public void deleteBacktest(@PathVariable Long id) {
        service.deleteBacktest(id);
    }

    @GetMapping("/comparisons")
    public ResultPage<ComparisonItem> comparisons(@RequestParam(required = false) Long userId,
                                                  @RequestParam(required = false) String symbol,
                                                  @RequestParam(required = false) String type,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return service.comparisons(userId, symbol, type, page, size);
    }

    @GetMapping("/comparisons/{id}")
    public ComparisonView comparison(@PathVariable Long id) {
        return service.comparison(id);
    }

    @DeleteMapping("/comparisons/{id}")
    public void deleteComparison(@PathVariable Long id) {
        service.deleteComparison(id);
    }

    // ----- statistiques -----

    @GetMapping("/stats")
    public Stats stats(@RequestParam(required = false) Integer weeks) {
        return service.stats(weeks);
    }

    // ----- cache de l'analyse fondamentale -----

    @GetMapping("/fundamentals")
    public List<CacheEntry> fundamentalsCache() {
        return fundamentals.cacheEntries();
    }

    @DeleteMapping("/fundamentals/{symbol}")
    public void deleteFundamentals(@PathVariable String symbol) {
        fundamentals.deleteCache(symbol);
    }
}
