package tn.esprit.marketsim.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.common.Hashing;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.ledger.LedgerCrypto.Step;
import tn.esprit.marketsim.ledger.LedgerDtos.BlockDto;
import tn.esprit.marketsim.ledger.LedgerDtos.ChainReport;
import tn.esprit.marketsim.ledger.LedgerDtos.Issue;
import tn.esprit.marketsim.ledger.LedgerDtos.ProofDto;
import tn.esprit.marketsim.ledger.LedgerDtos.RecordDetail;
import tn.esprit.marketsim.ledger.LedgerDtos.RecordDto;
import tn.esprit.marketsim.ledger.LedgerDtos.Summary;
import tn.esprit.marketsim.ledger.LedgerDtos.TamperResult;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;

/**
 * Registre d'audit des decisions de l'IA : chaque evenement est fige (JSON canonique + SHA-256), puis scelle
 * par lots dans des blocs lies entre eux (racine de Merkle + hash du bloc precedent + sceau HMAC).
 *
 * <p>Ce que cela garantit : toute modification apres coup d'une decision, d'une simulation ou d'un backtest
 * est detectee, et on peut en prouver l'authenticite avec un simple chemin de Merkle.
 * Ce que cela ne garantit pas seul : un administrateur qui possede la base ET le secret de scellage peut tout
 * reecrire. L'ancrage de {@code headHash} sur une blockchain publique (champ {@code anchorRef}) ferme cette limite.
 */
@Slf4j
@Service
public class AuditLedger {

    static final int PAGE_LIMIT = 200;
    private static final Pattern FIRST_NUMBER = Pattern.compile(":(-?\\d+(?:\\.\\d+)?)");

    private final LedgerRecordRepository records;
    private final LedgerBlockRepository blocks;
    private final TransactionTemplate tx;
    private final String secret;
    private final boolean demoTamper;
    private final long sealIntervalMs;

    private final ObjectMapper json = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public AuditLedger(LedgerRecordRepository records, LedgerBlockRepository blocks, PlatformTransactionManager txManager,
                       @Value("${app.ledger.secret}") String secret,
                       @Value("${app.ledger.demo-tamper:true}") boolean demoTamper,
                       @Value("${app.ledger.seal-interval-ms:15000}") long sealIntervalMs) {
        this.records = records;
        this.blocks = blocks;
        this.tx = new TransactionTemplate(txManager);
        this.secret = secret;
        this.demoTamper = demoTamper;
        this.sealIntervalMs = sealIntervalMs;
    }

    // ===================== Ecriture =====================

    /**
     * Enregistre un evenement (en attente de scellage). Ne leve jamais d'exception : un incident d'audit ne doit
     * pas bloquer le trading ; il est journalise.
     *
     * @return identifiant de l'enregistrement, ou null en cas d'echec
     */
    public Long record(String type, Long refId, Long ownerId, Long agentId, String title, Map<String, Object> payload) {
        try {
            LedgerRecord r = new LedgerRecord();
            r.setType(type);
            r.setRefId(refId);
            r.setOwnerId(ownerId);
            r.setAgentId(agentId);
            r.setTitle(title != null && title.length() > 250 ? title.substring(0, 250) : title);
            r.setCreatedAtMs(System.currentTimeMillis());
            r.setPayload(json.writeValueAsString(payload));
            r.setLeafHash(r.recomputedLeafHash());
            return records.save(r).getId();
        } catch (Exception e) {
            log.error("Journal d'audit : enregistrement {} impossible", type, e);
            return null;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureGenesis() {
        tx.executeWithoutResult(s -> genesis());
    }

    private LedgerBlock genesis() {
        return blocks.findTopByOrderByHeightDesc().orElseGet(() -> {
            LedgerBlock g = newBlock(0, LedgerCrypto.ZERO_HASH, List.of(), System.currentTimeMillis());
            log.info("Registre d'audit : bloc genesis cree ({})", g.getBlockHash());
            return blocks.save(g);
        });
    }

    private LedgerBlock newBlock(int height, String previousHash, List<String> leafHashes, long now) {
        LedgerBlock b = new LedgerBlock();
        b.setHeight(height);
        b.setPreviousHash(previousHash);
        b.setMerkleRoot(LedgerCrypto.merkleRoot(leafHashes));
        b.setRecordCount(leafHashes.size());
        b.setSealedAtMs(now);
        b.setBlockHash(LedgerCrypto.blockHash(height, previousHash, b.getMerkleRoot(), leafHashes.size(), now));
        b.setSeal(LedgerCrypto.seal(secret, b.getBlockHash()));
        return b;
    }

    /** Planificateur : scelle regulierement les enregistrements en attente. */
    @Scheduled(fixedDelayString = "${app.ledger.seal-interval-ms:15000}", initialDelayString = "${app.ledger.seal-interval-ms:15000}")
    public void sealScheduled() {
        try {
            seal();
        } catch (Exception e) {
            log.error("Journal d'audit : scellage impossible", e);
        }
    }

    /** Scelle les enregistrements en attente (500 max) dans un nouveau bloc. @return le bloc, ou null s'il n'y avait rien */
    public synchronized BlockDto seal() {
        LedgerBlock sealed = tx.execute(status -> {
            List<LedgerRecord> pending = records.findTop500ByBlockHeightIsNullOrderByIdAsc();
            if (pending.isEmpty()) return null;
            LedgerBlock last = genesis();
            int height = last.getHeight() + 1;
            List<String> leaves = pending.stream().map(LedgerRecord::getLeafHash).toList();
            LedgerBlock block = newBlock(height, last.getBlockHash(), leaves, System.currentTimeMillis());
            for (int i = 0; i < pending.size(); i++) {
                pending.get(i).setBlockHeight(height);
                pending.get(i).setLeafIndex(i);
            }
            records.saveAll(pending);
            return blocks.save(block);
        });
        return sealed == null ? null : toDto(sealed);
    }

    // ===================== Verification =====================

    /**
     * Reverifie toute la chaine : liens entre blocs, hash et sceau de chaque bloc, racine de Merkle recalculee a partir
     * du CONTENU actuel des enregistrements, et nombre d'enregistrements.
     */
    public ChainReport verify() {
        return tx.execute(status -> {
            List<Issue> issues = new ArrayList<>();
            List<LedgerBlock> chain = blocks.findAllByOrderByHeightAsc();
            String previous = LedgerCrypto.ZERO_HASH;
            long sealedRecords = 0;
            for (LedgerBlock b : chain) {
                int h = b.getHeight();
                if (!b.getPreviousHash().equals(previous)) {
                    issues.add(new Issue("LINK", h, null, "Le bloc " + h + " ne pointe plus sur le hash du bloc précédent."));
                }
                String expectedHash = LedgerCrypto.blockHash(h, b.getPreviousHash(), b.getMerkleRoot(),
                        b.getRecordCount(), b.getSealedAtMs());
                if (!expectedHash.equals(b.getBlockHash())) {
                    issues.add(new Issue("BLOCK_HASH", h, null, "Le hash du bloc " + h + " ne correspond plus à son contenu."));
                }
                if (!LedgerCrypto.seal(secret, b.getBlockHash()).equals(b.getSeal())) {
                    issues.add(new Issue("SEAL", h, null, "Le sceau du bloc " + h + " est invalide (bloc réécrit sans le secret serveur)."));
                }
                List<LedgerRecord> recs = records.findByBlockHeightOrderByLeafIndexAsc(h);
                sealedRecords += recs.size();
                if (recs.size() != b.getRecordCount()) {
                    issues.add(new Issue("RECORD_COUNT", h, null, "Le bloc " + h + " devrait contenir " + b.getRecordCount()
                            + " enregistrements, il en contient " + recs.size() + " (suppression ou ajout)."));
                }
                List<String> leaves = new ArrayList<>(recs.size());
                for (LedgerRecord r : recs) {
                    String actual = r.recomputedLeafHash();
                    leaves.add(actual);
                    if (!actual.equals(r.getLeafHash())) {
                        issues.add(new Issue("RECORD_HASH", h, r.getId(), "L'enregistrement #" + r.getId() + " ("
                                + r.getType() + ") a été modifié après son scellage dans le bloc " + h + "."));
                    }
                }
                if (recs.size() == b.getRecordCount() && !LedgerCrypto.merkleRoot(leaves).equals(b.getMerkleRoot())) {
                    issues.add(new Issue("MERKLE_ROOT", h, null, "La racine de Merkle du bloc " + h + " ne correspond plus à ses enregistrements."));
                }
                previous = b.getBlockHash();
            }
            boolean empty = chain.isEmpty();
            return new ChainReport(issues.isEmpty(), chain.size(), sealedRecords, records.countByBlockHeightIsNull(),
                    empty ? LedgerCrypto.ZERO_HASH : previous, issues.size() > 50 ? issues.subList(0, 50) : issues,
                    System.currentTimeMillis());
        });
    }

    /** Preuve d'inclusion d'un enregistrement (verifiable hors serveur, voir {@link ProofDto}). */
    public ProofDto proof(Long recordId, User user) {
        return tx.execute(status -> {
            LedgerRecord r = visible(recordId, user);
            boolean intact = r.recomputedLeafHash().equals(r.getLeafHash());
            if (!r.isSealed()) {
                return new ProofDto(r.getId(), r.getType(), false, intact, r.leafInput(), r.getLeafHash(), null, null,
                        List.of(), null, null, null, null, null, null, false);
            }
            LedgerBlock b = blocks.findById(r.getBlockHeight()).orElseThrow(() -> new NotFoundException("Bloc introuvable"));
            List<String> leaves = records.findByBlockHeightOrderByLeafIndexAsc(b.getHeight()).stream()
                    .map(LedgerRecord::getLeafHash).toList();
            List<Step> path = LedgerCrypto.proof(leaves, r.getLeafIndex());
            return new ProofDto(r.getId(), r.getType(), true, intact, r.leafInput(), r.getLeafHash(), r.getLeafIndex(),
                    leaves.size(), path, b.getMerkleRoot(), b.getHeight(), b.getBlockHash(), b.getPreviousHash(),
                    LedgerCrypto.blockInput(b.getHeight(), b.getPreviousHash(), b.getMerkleRoot(), b.getRecordCount(),
                            b.getSealedAtMs()),
                    b.getSealedAtMs(), LedgerCrypto.verifyProof(r.getLeafHash(), path, b.getMerkleRoot()));
        });
    }

    // ===================== Lecture =====================

    public Summary summary(User user) {
        Long scope = scope(user);
        LedgerBlock head = blocks.findTopByOrderByHeightDesc().orElse(null);
        Map<String, Long> byType = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : records.countByType(scope)) {
            byType.put((String) row[0], (Long) row[1]);
            total += (Long) row[1];
        }
        return new Summary((int) blocks.count(), head == null ? -1 : head.getHeight(),
                head == null ? LedgerCrypto.ZERO_HASH : head.getBlockHash(), head == null ? null : head.getSealedAtMs(),
                records.count(), total, records.countByBlockHeightIsNull(), byType,
                (sealIntervalMs / 1000) + " s");
    }

    public List<RecordDto> list(User user, String type, Long agentId, int limit) {
        return records.search(scope(user), blankToNull(type), agentId, PageRequest.of(0, Math.max(1, Math.min(PAGE_LIMIT, limit))))
                .stream().map(AuditLedger::toDto).toList();
    }

    public RecordDetail detail(Long id, User user) {
        LedgerRecord r = visible(id, user);
        return new RecordDetail(toDto(r), r.getPayload(), r.leafInput());
    }

    public List<BlockDto> blocks(int limit) {
        return blocks.findAllByOrderByHeightDesc(PageRequest.of(0, Math.max(1, Math.min(PAGE_LIMIT, limit))))
                .stream().map(AuditLedger::toDto).toList();
    }

    // ===================== Demonstration de falsification =====================

    /** Demo : modifie directement le contenu stocke d'un enregistrement (comme le ferait un attaquant sur la base). */
    public TamperResult tamper(Long id, User user) {
        requireDemo();
        return tx.execute(status -> {
            LedgerRecord r = visible(id, user);
            if (r.getTamperBackup() != null) throw new IllegalArgumentException("Cet enregistrement est déjà falsifié");
            String payload = r.getPayload();
            String changed;
            String description;
            if (payload.contains("\"action\":\"BUY\"")) {
                changed = payload.replace("\"action\":\"BUY\"", "\"action\":\"SELL\"");
                description = "action : BUY → SELL";
            } else if (payload.contains("\"action\":\"SELL\"")) {
                changed = payload.replace("\"action\":\"SELL\"", "\"action\":\"BUY\"");
                description = "action : SELL → BUY";
            } else if (payload.contains("\"action\":\"HOLD\"")) {
                changed = payload.replace("\"action\":\"HOLD\"", "\"action\":\"BUY\"");
                description = "action : HOLD → BUY";
            } else {
                Matcher m = FIRST_NUMBER.matcher(payload);
                if (!m.find()) throw new IllegalArgumentException("Rien à falsifier dans cet enregistrement");
                double doubled = Double.parseDouble(m.group(1)) * 2 + 1;
                changed = payload.substring(0, m.start(1)) + doubled + payload.substring(m.end(1));
                description = "valeur " + m.group(1) + " → " + doubled;
            }
            r.setTamperBackup(payload);
            r.setPayload(changed);
            records.save(r);
            return new TamperResult(r.getId(), true, description);
        });
    }

    /** Demo : remet le contenu d'origine. */
    public TamperResult restore(Long id, User user) {
        requireDemo();
        return tx.execute(status -> {
            LedgerRecord r = visible(id, user);
            if (r.getTamperBackup() == null) throw new IllegalArgumentException("Cet enregistrement n'a pas été falsifié");
            r.setPayload(r.getTamperBackup());
            r.setTamperBackup(null);
            records.save(r);
            return new TamperResult(r.getId(), false, "contenu d'origine restauré");
        });
    }

    private void requireDemo() {
        if (!demoTamper) throw new IllegalArgumentException("La démonstration de falsification est désactivée");
    }

    // ===================== Outils =====================

    private LedgerRecord visible(Long id, User user) {
        LedgerRecord r = records.findById(id).orElseThrow(() -> new NotFoundException("Enregistrement introuvable"));
        if (user.getRole() != Role.ADMIN && !user.getId().equals(r.getOwnerId())) {
            throw new NotFoundException("Enregistrement introuvable");
        }
        return r;
    }

    /** Un utilisateur ne voit que ses enregistrements ; l'administrateur voit tout (null = pas de filtre). */
    private static Long scope(User user) {
        return user.getRole() == Role.ADMIN ? null : user.getId();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static RecordDto toDto(LedgerRecord r) {
        return new RecordDto(r.getId(), r.getType(), r.getRefId(), r.getAgentId(), r.getTitle(), r.getCreatedAtMs(),
                r.getLeafHash(), r.getBlockHeight(), r.getLeafIndex(), r.isSealed(),
                r.recomputedLeafHash().equals(r.getLeafHash()));
    }

    static BlockDto toDto(LedgerBlock b) {
        return new BlockDto(b.getHeight(), b.getPreviousHash(), b.getMerkleRoot(), b.getRecordCount(), b.getSealedAtMs(),
                b.getBlockHash(), b.getSeal(), b.getAnchorRef());
    }

    /** Empreinte d'un texte (pour les politiques et jeux de donnees cites dans les enregistrements). */
    public static String fingerprint(String text) {
        return Hashing.sha256Hex(text);
    }
}
