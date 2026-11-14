package tn.esprit.marketsim.progression;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.AdminTraderScore;
import tn.esprit.marketsim.user.User;

/** Back-office : portefeuille, niveau et score de trading de tous les utilisateurs et ajustement du portefeuille. */
@RestController
@RequestMapping("/api/admin/progression")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminProgressionController {

    private final ProgressionService service;
    private final AdminWalletService adminWallet;

    @GetMapping
    public List<AdminTraderScore> all() {
        return service.allScores();
    }

    /** Fixe le solde du portefeuille d'un trader (motif obligatoire, trace dans le journal d'activite). */
    @PostMapping("/{userId}/balance")
    public WalletService.Adjustment setBalance(@PathVariable Long userId, @RequestBody AdminWalletService.SetBalanceRequest req,
                                               @AuthenticationPrincipal User admin) {
        return adminWallet.setBalance(admin, userId, req);
    }
}
