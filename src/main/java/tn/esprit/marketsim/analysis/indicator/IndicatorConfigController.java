package tn.esprit.marketsim.analysis.indicator;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.indicator.IndicatorConfigDtos.IndicatorConfigRequest;
import tn.esprit.marketsim.analysis.indicator.IndicatorConfigDtos.IndicatorConfigView;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : configurations d'indicateurs de l'utilisateur connecte (onglet Analyse technique).
 *
 * <pre>
 *   GET    /api/analysis/indicator-configs          mes configurations (ordre alphabetique)
 *   POST   /api/analysis/indicator-configs          cree une configuration (409 si le nom est deja pris)
 *   GET    /api/analysis/indicator-configs/{id}     une configuration
 *   PUT    /api/analysis/indicator-configs/{id}     remplace une configuration (mise a jour, renommage)
 *   DELETE /api/analysis/indicator-configs/{id}     supprime une configuration
 * </pre>
 */
@RestController
@RequestMapping("/api/analysis/indicator-configs")
@RequiredArgsConstructor
public class IndicatorConfigController {

    private final IndicatorConfigService service;

    @GetMapping
    public List<IndicatorConfigView> list(@AuthenticationPrincipal User user) {
        return service.findMine(user);
    }

    @PostMapping
    public IndicatorConfigView create(@RequestBody IndicatorConfigRequest request, @AuthenticationPrincipal User user) {
        return service.create(request, user);
    }

    @GetMapping("/{id}")
    public IndicatorConfigView get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.findOne(id, user);
    }

    @PutMapping("/{id}")
    public IndicatorConfigView update(@PathVariable Long id, @RequestBody IndicatorConfigRequest request,
                                      @AuthenticationPrincipal User user) {
        return service.update(id, request, user);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.delete(id, user);
    }
}
