package tn.esprit.marketsim.ledger;

import java.util.List;
import java.util.Map;

import tn.esprit.marketsim.ledger.LedgerCrypto.Step;

/** Reponses de l'API du registre d'audit. */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    /**
     * @param sealed rattache a un bloc (sinon : en attente de scellage)
     * @param intact le contenu actuel correspond a l'empreinte enregistree (faux apres une falsification)
     */
    public record RecordDto(Long id, String type, Long refId, Long agentId, String title, long createdAtMs,
                            String leafHash, Integer blockHeight, Integer leafIndex, boolean sealed, boolean intact) {
    }

    public record RecordDetail(RecordDto record, String payload, String leafInput) {
    }

    public record BlockDto(int height, String previousHash, String merkleRoot, int recordCount, long sealedAtMs,
                           String blockHash, String seal, String anchorRef) {
    }

    /**
     * Tout ce qu'il faut pour verifier un enregistrement sans faire confiance au serveur :
     * {@code SHA-256(leafInput) = leafHash}, puis le chemin de Merkle mene a {@code merkleRoot},
     * puis {@code SHA-256(blockInput) = blockHash}.
     *
     * @param proofValid le chemin mene bien a la racine du bloc (calcul serveur)
     */
    public record ProofDto(Long recordId, String type, boolean sealed, boolean intact, String leafInput, String leafHash,
                           Integer leafIndex, Integer leafCount, List<Step> path, String merkleRoot, Integer blockHeight,
                           String blockHash, String previousHash, String blockInput, Long sealedAtMs, boolean proofValid) {
    }

    public record Summary(int blocks, int headHeight, String headHash, Long lastSealedAtMs, long totalRecords,
                          long myRecords, long pendingRecords, Map<String, Long> byType, String sealInterval) {
    }

    /** @param type RECORD_HASH, RECORD_COUNT, MERKLE_ROOT, BLOCK_HASH, LINK ou SEAL */
    public record Issue(String type, Integer blockHeight, Long recordId, String message) {
    }

    public record ChainReport(boolean valid, int blocks, long sealedRecords, long pendingRecords, String headHash,
                              List<Issue> issues, long checkedAtMs) {
    }

    public record TamperResult(Long recordId, boolean tampered, String change) {
    }
}
