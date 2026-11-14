package tn.esprit.marketsim.user.passkey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.user.passkey.ChallengeStore.Purpose;

class ChallengeStoreTest {

    @Test
    void aChallengeCanBeUsedOnlyOnceForItsPurposeAndUser() {
        ChallengeStore store = new ChallengeStore();
        String c = store.issue(Purpose.REGISTER, 5L);
        assertFalse(store.consume(c, Purpose.LOGIN, null));      // mauvais usage : consomme et refuse
        assertFalse(store.consume(c, Purpose.REGISTER, 5L));     // deja consomme
        String d = store.issue(Purpose.REGISTER, 5L);
        assertFalse(store.consume(d, Purpose.REGISTER, 6L));     // autre utilisateur
        String e = store.issue(Purpose.LOGIN, null);
        assertTrue(store.consume(e, Purpose.LOGIN, null));
        assertFalse(store.consume(e, Purpose.LOGIN, null));      // rejeu
    }
}
