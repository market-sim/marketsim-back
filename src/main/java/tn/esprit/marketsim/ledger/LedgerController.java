package tn.esprit.marketsim.ledger;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.ledger.LedgerDtos.BlockDto;
import tn.esprit.marketsim.ledger.LedgerDtos.ChainReport;
import tn.esprit.marketsim.ledger.LedgerDtos.ProofDto;
import tn.esprit.marketsim.ledger.LedgerDtos.RecordDetail;
import tn.esprit.marketsim.ledger.LedgerDtos.RecordDto;
import tn.esprit.marketsim.ledger.LedgerDtos.Summary;
import tn.esprit.marketsim.ledger.LedgerDtos.TamperResult;
import tn.esprit.marketsim.user.User;

/** Registre d'audit : consultation, verification de la chaine, preuves de Merkle. */
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final AuditLedger ledger;

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal User user) {
        return ledger.summary(user);
    }

    @GetMapping("/records")
    public List<RecordDto> records(@RequestParam(required = false) String type,
                                   @RequestParam(required = false) Long agentId,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @AuthenticationPrincipal User user) {
        return ledger.list(user, type, agentId, limit);
    }

    @GetMapping("/records/{id}")
    public RecordDetail record(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ledger.detail(id, user);
    }

    @GetMapping("/records/{id}/proof")
    public ProofDto proof(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ledger.proof(id, user);
    }

    @GetMapping("/blocks")
    public List<BlockDto> blocks(@RequestParam(defaultValue = "30") int limit) {
        return ledger.blocks(limit);
    }

    @GetMapping("/verify")
    public ChainReport verify() {
        return ledger.verify();
    }

    /** Scelle tout de suite les enregistrements en attente (sinon le planificateur le fait toutes les 15 s). */
    @PostMapping("/seal")
    public ResponseEntity<?> seal() {
        BlockDto block = ledger.seal();
        return block == null ? ResponseEntity.ok(Map.of("message", "Aucun enregistrement en attente")) : ResponseEntity.ok(block);
    }

    // ----- Demonstration : falsifier / restaurer un enregistrement directement en base -----

    @PostMapping("/demo/tamper/{id}")
    public TamperResult tamper(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ledger.tamper(id, user);
    }

    @PostMapping("/demo/restore/{id}")
    public TamperResult restore(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ledger.restore(id, user);
    }
}
