package tn.esprit.marketsim.user.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private final TotpService totp = new TotpService();
    /** Secret de reference de la RFC 6238 (« 12345678901234567890 ») en base32. */
    private static final String RFC_SECRET = TotpService.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesRfc6238TestVectors() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", RFC_SECRET);
        // valeurs de la RFC (8 chiffres) tronquees a 6 chiffres
        assertEquals("287082", TotpService.code(RFC_SECRET, 59 / 30));
        assertEquals("081804", TotpService.code(RFC_SECRET, 1111111109L / 30));
        assertEquals("050471", TotpService.code(RFC_SECRET, 1111111111L / 30));
    }

    @Test
    void acceptsNeighbourStepsOnly() {
        long now = 1_111_111_109L;
        String code = TotpService.code(RFC_SECRET, now / 30);
        assertTrue(totp.verify(RFC_SECRET, code, now));
        assertTrue(totp.verify(RFC_SECRET, code, now + 30));
        assertFalse(totp.verify(RFC_SECRET, code, now + 120));
        assertFalse(totp.verify(RFC_SECRET, "12345", now));
        assertFalse(totp.verify(null, code, now));
    }

    @Test
    void secretRoundTripsAndUriIsWellFormed() {
        String secret = totp.newSecret();
        assertEquals(32, secret.length());
        assertEquals(secret, TotpService.base32(TotpService.decodeBase32(secret)));
        String uri = totp.otpauthUri(secret, "yasmine@test.tn");
        assertTrue(uri.startsWith("otpauth://totp/MarketSim%20AI%3Ayasmine%40test.tn?secret=" + secret));
        assertTrue(uri.contains("issuer=MarketSim%20AI"));
    }
}
