package tn.esprit.marketsim.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bloc du registre d'audit : lie les enregistrements scelles (racine de Merkle) au bloc precedent (hash chaine).
 * Modifier un enregistrement change sa feuille, donc la racine, donc le hash du bloc, donc tous les suivants.
 */
@Entity
@Table(name = "ledger_block")
@Getter
@Setter
@NoArgsConstructor
public class LedgerBlock {

    /** Numero du bloc : 0 = bloc genesis. */
    @Id
    private Integer height;

    @Column(nullable = false, length = 64)
    private String previousHash;

    @Column(nullable = false, length = 64)
    private String merkleRoot;

    private int recordCount;

    private long sealedAtMs;

    @Column(nullable = false, length = 64)
    private String blockHash;

    /** HMAC-SHA256(secret serveur, blockHash). */
    @Column(nullable = false, length = 64)
    private String seal;

    /** Reference d'un ancrage externe (blockchain publique) quand il est configure ; nul sinon. */
    private String anchorRef;
}
