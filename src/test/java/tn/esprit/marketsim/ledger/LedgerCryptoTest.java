package tn.esprit.marketsim.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.common.Hashing;
import tn.esprit.marketsim.ledger.LedgerCrypto.Step;

/** Le coeur du registre d'audit : hash des feuilles, arbre de Merkle, preuves, hash des blocs. Sans base de donnees. */
class LedgerCryptoTest {

    private static List<String> leaves(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(LedgerCrypto.leafHash("DECISION", (long) i, 1L, 7L, 1_700_000_000_000L + i, "{\"action\":\"BUY\",\"i\":" + i + "}"));
        }
        return out;
    }

    @Test
    void sha256MatchesTheStandardTestVector() {
        // SHA-256("abc") : vecteur de test du standard FIPS 180-2
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc"));
    }

    @Test
    void everyLeafHasAValidProofForEveryTreeSize() {
        for (int n = 1; n <= 40; n++) {
            List<String> leaves = leaves(n);
            String root = LedgerCrypto.merkleRoot(leaves);
            for (int i = 0; i < n; i++) {
                List<Step> path = LedgerCrypto.proof(leaves, i);
                assertTrue(LedgerCrypto.verifyProof(leaves.get(i), path, root), "feuille " + i + "/" + n);
            }
        }
    }

    @Test
    void aSingleLeafHasAnEmptyPathAndItsOwnRoot() {
        List<String> one = leaves(1);
        assertTrue(LedgerCrypto.proof(one, 0).isEmpty());
        assertTrue(LedgerCrypto.verifyProof(one.get(0), List.of(), LedgerCrypto.merkleRoot(one)));
    }

    @Test
    void proofPathLengthIsLogarithmic() {
        List<String> many = leaves(500);
        assertTrue(LedgerCrypto.proof(many, 123).size() <= 9, "ceil(log2(500)) = 9");
    }

    @Test
    void aTamperedLeafIsRejectedByItsProofAndChangesTheRoot() {
        List<String> leaves = leaves(10);
        String root = LedgerCrypto.merkleRoot(leaves);
        List<Step> path = LedgerCrypto.proof(leaves, 4);
        String forged = LedgerCrypto.leafHash("DECISION", 4L, 1L, 7L, 1_700_000_000_004L, "{\"action\":\"SELL\",\"i\":4}");
        assertFalse(LedgerCrypto.verifyProof(forged, path, root));

        List<String> modified = new ArrayList<>(leaves);
        modified.set(4, forged);
        assertNotEquals(root, LedgerCrypto.merkleRoot(modified));
    }

    @Test
    void aProofDoesNotValidateAnotherLeafOrAnotherRoot() {
        List<String> leaves = leaves(8);
        String root = LedgerCrypto.merkleRoot(leaves);
        List<Step> pathOfThree = LedgerCrypto.proof(leaves, 3);
        assertFalse(LedgerCrypto.verifyProof(leaves.get(5), pathOfThree, root));
        assertFalse(LedgerCrypto.verifyProof(leaves.get(3), pathOfThree, LedgerCrypto.merkleRoot(leaves(9))));
    }

    @Test
    void leafOrderMatters() {
        List<String> a = leaves(4);
        List<String> b = new ArrayList<>(a);
        java.util.Collections.swap(b, 0, 1);
        assertNotEquals(LedgerCrypto.merkleRoot(a), LedgerCrypto.merkleRoot(b));
    }

    @Test
    void anInnerNodeCannotBePassedOffAsALeaf() {
        // Les prefixes 0x00 (feuille) / 0x01 (noeud interne) bloquent l'attaque de seconde pre-image :
        // avec 2 feuilles, la racine ne peut pas etre presentee comme la feuille d'un arbre a 1 element.
        List<String> two = leaves(2);
        String root = LedgerCrypto.merkleRoot(two);
        assertNotEquals(root, LedgerCrypto.merkleRoot(List.of(root)));
    }

    @Test
    void leafHashCoversEveryField() {
        String base = LedgerCrypto.leafHash("DECISION", 1L, 2L, 3L, 1000L, "{}");
        assertNotEquals(base, LedgerCrypto.leafHash("MONTE_CARLO", 1L, 2L, 3L, 1000L, "{}"));
        assertNotEquals(base, LedgerCrypto.leafHash("DECISION", 9L, 2L, 3L, 1000L, "{}"));
        assertNotEquals(base, LedgerCrypto.leafHash("DECISION", 1L, 9L, 3L, 1000L, "{}"));
        assertNotEquals(base, LedgerCrypto.leafHash("DECISION", 1L, 2L, 9L, 1000L, "{}"));
        assertNotEquals(base, LedgerCrypto.leafHash("DECISION", 1L, 2L, 3L, 1001L, "{}"));
        assertNotEquals(base, LedgerCrypto.leafHash("DECISION", 1L, 2L, 3L, 1000L, "{ }"));
        assertEquals(base, LedgerCrypto.leafHash("DECISION", 1L, 2L, 3L, 1000L, "{}"));
        // le texte hache est reproductible a la main
        assertEquals(Hashing.sha256Hex("REC1|DECISION|1|2|3|1000|{}"), base);
    }

    @Test
    void blockHashChainsToThePreviousBlockAndTheSealNeedsTheSecret() {
        String genesis = LedgerCrypto.blockHash(0, LedgerCrypto.ZERO_HASH, LedgerCrypto.merkleRoot(List.of()), 0, 1L);
        String root = LedgerCrypto.merkleRoot(leaves(3));
        String b1 = LedgerCrypto.blockHash(1, genesis, root, 3, 2L);
        assertNotEquals(b1, LedgerCrypto.blockHash(1, LedgerCrypto.ZERO_HASH, root, 3, 2L), "le hash precedent compte");
        assertNotEquals(b1, LedgerCrypto.blockHash(1, genesis, LedgerCrypto.merkleRoot(leaves(4)), 3, 2L), "la racine compte");
        assertNotEquals(b1, LedgerCrypto.blockHash(1, genesis, root, 4, 2L), "le nombre compte");
        assertNotEquals(b1, LedgerCrypto.blockHash(1, genesis, root, 3, 3L), "la date compte");

        String seal = LedgerCrypto.seal("secret-A", b1);
        assertEquals(seal, LedgerCrypto.seal("secret-A", b1));
        assertNotEquals(seal, LedgerCrypto.seal("secret-B", b1), "sans le bon secret, le sceau ne se reproduit pas");
        assertEquals(64, seal.length());
    }

    @Test
    void hmacMatchesTheRfc4231TestVector() {
        // RFC 4231, test case 2 : cle "Jefe", message "what do ya want for nothing?"
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                LedgerCrypto.seal("Jefe", "what do ya want for nothing?"));
    }
}
