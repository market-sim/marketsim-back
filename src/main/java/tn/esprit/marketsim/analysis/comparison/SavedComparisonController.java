package tn.esprit.marketsim.analysis.comparison;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.ComparisonDetail;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.ComparisonSummary;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.RenameRequest;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.SaveComparisonRequest;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : comparaisons enregistrees de l'utilisateur connecte. La comparaison a la demande, sans
 * enregistrement, reste {@code POST /api/analysis/backtests/compare}.
 *
 * <pre>
 *   POST   /api/analysis/comparisons          relance la comparaison demandee et l'enregistre
 *   GET    /api/analysis/comparisons          mes comparaisons (resumes, sans les courbes)
 *   GET    /api/analysis/comparisons/{id}     une comparaison complete
 *   PATCH  /api/analysis/comparisons/{id}     renomme : { "name": "..." }
 *   DELETE /api/analysis/comparisons/{id}     supprime
 * </pre>
 */
@RestController
@RequestMapping("/api/analysis/comparisons")
@RequiredArgsConstructor
public class SavedComparisonController {

    private final SavedComparisonService service;

    @PostMapping
    public ComparisonDetail save(@RequestBody SaveComparisonRequest request, @AuthenticationPrincipal User user) {
        return service.save(request, user);
    }

    @GetMapping
    public List<ComparisonSummary> list(@AuthenticationPrincipal User user) {
        return service.findMine(user);
    }

    @GetMapping("/{id}")
    public ComparisonDetail get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.findOne(id, user);
    }

    @PatchMapping("/{id}")
    public ComparisonSummary rename(@PathVariable Long id, @RequestBody RenameRequest request,
                                    @AuthenticationPrincipal User user) {
        return service.rename(id, request, user);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.delete(id, user);
    }
}
