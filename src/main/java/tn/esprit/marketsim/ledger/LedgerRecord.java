package tn.esprit.marketsim.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Un evenement audite (decision d'un agent, simulation, tournoi, backtest...). Le contenu exact est fige dans
 * {@code payload} (JSON canonique) et empreinte dans {@code leafHash}. Tant que {@code blockHeight} est nul,
 * l'enregistrement attend d'etre scelle dans un bloc.
 */
@Entity
@Table(name = "ledger_record", indexes = {
        @Index(name = "idx_ledger_record_owner", columnList = "ownerId"),
        @Index(name = "idx_ledger_record_block", columnList = "blockHeight")})
@Getter
@Setter
@NoArgsConstructor
public class LedgerRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String type;

    /** Identifiant de l'objet audite dans son propre module (decision, simulation...), s'il existe. */
    private Long refId;

    private Long ownerId;

    private Long agentId;

    @Column(length = 255)
    private String title;

    @Column(nullable = false)
    private long createdAtMs;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(nullable = false, length = 64)
    private String leafHash;

    private Integer blockHeight;

    private Integer leafIndex;

    /** Demo uniquement : contenu d'origine pendant qu'une falsification simulee est en place. */
    @Column(columnDefinition = "LONGTEXT")
    private String tamperBackup;

    public boolean isSealed() {
        return blockHeight != null;
    }

    /** Le contenu actuel correspond-il a l'empreinte enregistree ? */
    public String recomputedLeafHash() {
        return LedgerCrypto.leafHash(type, refId, ownerId, agentId, createdAtMs, payload);
    }

    public String leafInput() {
        return LedgerCrypto.leafInput(type, refId, ownerId, agentId, createdAtMs, payload);
    }
}
