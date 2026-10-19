package tn.esprit.marketsim.ledger;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tn.esprit.marketsim.common.Hashing;

/**
 * Primitives cryptographiques du journal d'audit (aucune dependance a Spring, donc testables seules).
 *
 * <ul>
 *   <li><b>Feuille</b> : SHA-256 de {@code REC1|type|ref|owner|agent|date|payload}.</li>
 *   <li><b>Arbre de Merkle</b> (style RFC 6962) : noeud feuille = H(0x00 || feuille), noeud interne
 *       = H(0x01 || gauche || droite), noeud sans frere remonte tel quel. Les prefixes 0x00 / 0x01 empechent
 *       de faire passer un noeud interne pour une feuille (attaque de seconde pre-image).</li>
 *   <li><b>Bloc</b> : SHA-256 de {@code BLOCK1|hauteur|hash precedent|racine de Merkle|nombre|date}.</li>
 *   <li><b>Sceau</b> : HMAC-SHA256 du hash du bloc avec un secret serveur. Sans lui, un attaquant qui reecrit
 *       la base peut recalculer tous les hashs, mais pas les sceaux.</li>
 * </ul>
 */
public final class LedgerCrypto {

    public static final String ZERO_HASH = "0".repeat(64);

    private static final HexFormat HEX = HexFormat.of();

    private LedgerCrypto() {
    }

    /** Un pas du chemin de preuve : hash du frere et cote ou il se trouve. */
    public record Step(String hash, boolean left) {
    }

    // ===================== Feuilles et blocs =====================

    /** Texte exact dont le SHA-256 est la feuille : permet de refaire le calcul a la main. */
    public static String leafInput(String type, Long refId, Long ownerId, Long agentId, long createdAtMs, String payload) {
        return String.join("|", "REC1", type, String.valueOf(refId), String.valueOf(ownerId), String.valueOf(agentId),
                String.valueOf(createdAtMs), payload);
    }

    public static String leafHash(String type, Long refId, Long ownerId, Long agentId, long createdAtMs, String payload) {
        return Hashing.sha256Hex(leafInput(type, refId, ownerId, agentId, createdAtMs, payload));
    }

    public static String blockInput(int height, String previousHash, String merkleRoot, int recordCount, long sealedAtMs) {
        return String.join("|", "BLOCK1", String.valueOf(height), previousHash, merkleRoot,
                String.valueOf(recordCount), String.valueOf(sealedAtMs));
    }

    public static String blockHash(int height, String previousHash, String merkleRoot, int recordCount, long sealedAtMs) {
        return Hashing.sha256Hex(blockInput(height, previousHash, merkleRoot, recordCount, sealedAtMs));
    }

    public static String seal(String secret, String blockHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(blockHash.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    // ===================== Arbre de Merkle =====================

    private static byte[] leafNode(String leafHashHex) {
        byte[] leaf = HEX.parseHex(leafHashHex);
        byte[] in = new byte[leaf.length + 1];
        System.arraycopy(leaf, 0, in, 1, leaf.length);   // in[0] = 0x00
        return Hashing.sha256(in);
    }

    private static byte[] innerNode(byte[] left, byte[] right) {
        byte[] in = new byte[1 + left.length + right.length];
        in[0] = 0x01;
        System.arraycopy(left, 0, in, 1, left.length);
        System.arraycopy(right, 0, in, 1 + left.length, right.length);
        return Hashing.sha256(in);
    }

    private static List<byte[]> nextLevel(List<byte[]> level) {
        List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
        for (int i = 0; i < level.size(); i += 2) {
            next.add(i + 1 < level.size() ? innerNode(level.get(i), level.get(i + 1)) : level.get(i));
        }
        return next;
    }

    private static List<byte[]> leaves(List<String> leafHashes) {
        List<byte[]> level = new ArrayList<>(leafHashes.size());
        for (String h : leafHashes) level.add(leafNode(h));
        return level;
    }

    /** Racine de Merkle ; un bloc sans enregistrement (genesis) a une racine fixe. */
    public static String merkleRoot(List<String> leafHashes) {
        if (leafHashes.isEmpty()) return Hashing.sha256Hex("MarketSim AI audit ledger");
        List<byte[]> level = leaves(leafHashes);
        while (level.size() > 1) level = nextLevel(level);
        return HEX.formatHex(level.get(0));
    }

    /** Chemin de la feuille {@code index} jusqu'a la racine (vide si la feuille est seule dans son bloc). */
    public static List<Step> proof(List<String> leafHashes, int index) {
        if (index < 0 || index >= leafHashes.size()) throw new IllegalArgumentException("Feuille inconnue");
        List<Step> path = new ArrayList<>();
        List<byte[]> level = leaves(leafHashes);
        int i = index;
        while (level.size() > 1) {
            if (i % 2 == 1) {
                path.add(new Step(HEX.formatHex(level.get(i - 1)), true));
            } else if (i + 1 < level.size()) {
                path.add(new Step(HEX.formatHex(level.get(i + 1)), false));
            }
            level = nextLevel(level);
            i /= 2;
        }
        return path;
    }

    /** Verifie une preuve sans acces a la base : feuille + chemin = racine attendue ? */
    public static boolean verifyProof(String leafHash, List<Step> path, String merkleRoot) {
        byte[] current = leafNode(leafHash);
        for (Step s : path) {
            byte[] sibling = HEX.parseHex(s.hash());
            current = s.left() ? innerNode(sibling, current) : innerNode(current, sibling);
        }
        return HEX.formatHex(current).equals(merkleRoot);
    }
}
