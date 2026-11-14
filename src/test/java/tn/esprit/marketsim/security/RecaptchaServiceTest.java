package tn.esprit.marketsim.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecaptchaServiceTest {

    /** Simule la reponse de Google (true, false, ou null = injoignable). */
    private static RecaptchaService with(Boolean google, boolean failOpen) {
        return new RecaptchaService("site", "secret", failOpen) {
            @Override
            protected Boolean callGoogle(String token, String remoteIp) {
                return google;
            }
        };
    }

    @Test
    void disabledWithoutKeys() {
        RecaptchaService off = new RecaptchaService("", "", false);
        assertFalse(off.enabled());
        assertDoesNotThrow(() -> off.verify(null, null));
    }

    @Test
    void requiresATickedBox() {
        RecaptchaService r = with(true, false);
        assertTrue(r.enabled());
        assertThrows(IllegalArgumentException.class, () -> r.verify("", "1.2.3.4"));
        assertDoesNotThrow(() -> r.verify("jeton", "1.2.3.4"));
    }

    @Test
    void refusesWhatGoogleRefuses() {
        assertThrows(IllegalArgumentException.class, () -> with(false, true).verify("jeton", null));
    }

    @Test
    void unreachableGoogleFollowsTheFailOpenSetting() {
        assertThrows(IllegalArgumentException.class, () -> with(null, false).verify("jeton", null));
        assertDoesNotThrow(() -> with(null, true).verify("jeton", null));
    }
}
