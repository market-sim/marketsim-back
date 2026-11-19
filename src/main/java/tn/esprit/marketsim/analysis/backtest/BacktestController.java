package tn.esprit.marketsim.analysis.backtest;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestDetail;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestRequest;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.CompareRequest;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.ComparisonResult;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.DataCheckResult;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : backtests de l'utilisateur connecte.
 *
 * <pre>
 *   POST   /api/analysis/backtests            lance un backtest et l'enregistre
 *   GET    /api/analysis/backtests            mes backtests (resumes), ?strategyId= pour filtrer
 *   GET    /api/analysis/backtests/{id}       un backtest complet (metriques, courbes, trades)
 *   GET    /api/analysis/backtests/{id}/data-check   les cours de sa periode ont-ils change depuis ?
 *   DELETE /api/analysis/backtests/{id}       supprime un backtest
 * </pre>
 */
@RestController
@RequestMapping("/api/analysis/backtests")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService service;

    @PostMapping
    public BacktestDetail run(@RequestBody BacktestRequest request, @AuthenticationPrincipal User user) {
        return service.run(request, user);
    }

    @PostMapping("/compare")
    public ComparisonResult compare(@RequestBody CompareRequest request, @AuthenticationPrincipal User user) {
        return service.compare(request, user);
    }

    @GetMapping
    public List<BacktestSummary> list(@RequestParam(required = false) Long strategyId,
                                      @AuthenticationPrincipal User user) {
        return service.findMine(user, strategyId);
    }

    @GetMapping("/{id}")
    public BacktestDetail get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.findOne(id, user);
    }

    /** Les cours de la periode testee sont-ils encore les memes ? (SAME, CHANGED, NOT_COVERED, UNVERIFIABLE...) */
    @GetMapping("/{id}/data-check")
    public DataCheckResult dataCheck(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.dataCheck(id, user);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.delete(id, user);
    }
}
