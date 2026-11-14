package tn.esprit.marketsim.user.passkey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.user.passkey.WebAuthn.WebAuthnException;

/**
 * Simule un authentificateur (comme Face ID / Windows Hello) : vraie paire de cles P-256, donnees signees
 * exactement comme un navigateur les envoie, puis verifie ce que le serveur accepte ou refuse.
 */
class WebAuthnTest {

    static final String RP = "localhost";
    static final String ORIGIN = "http://localhost:5173";
    static final Set<String> ORIGINS = Set.of(ORIGIN);

    KeyPair keys;
    byte[] credId = "credential-123".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        keys = g.generateKeyPair();
    }

    // ─── mini encodeur CBOR (le serveur, lui, ne fait que decoder) ───────────────────────────
    static void head(ByteArrayOutputStream o, int major, long v) {
        if (v < 24) o.write((major << 5) | (int) v);
        else if (v < 256) { o.write((major << 5) | 24); o.write((int) v); }
        else { o.write((major << 5) | 25); o.write((int) (v >> 8)); o.write((int) v); }
    }

    static void enc(ByteArrayOutputStream o, Object x) {
        if (x instanceof Long l) {
            if (l >= 0) head(o, 0, l); else head(o, 1, -1 - l);
        } else if (x instanceof Integer i) {
            enc(o, (long) i);
        } else if (x instanceof byte[] b) {
            head(o, 2, b.length);
            o.writeBytes(b);
        } else if (x instanceof String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            head(o, 3, b.length);
            o.writeBytes(b);
        } else if (x instanceof Map<?, ?> m) {
            head(o, 5, m.size());
            m.forEach((k, v) -> { enc(o, k); enc(o, v); });
        } else if (x instanceof List<?> l) {
            head(o, 4, l.size());
            l.forEach(v -> enc(o, v));
        } else throw new IllegalArgumentException();
    }

    static byte[] cbor(Object x) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        enc(o, x);
        return o.toByteArray();
    }

    static byte[] fixed32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(32, b.length);
        System.arraycopy(b, b.length - n, out, 32 - n, n);
        return out;
    }

    byte[] coseKey() {
        ECPublicKey pk = (ECPublicKey) keys.getPublic();
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1, 2);           // kty EC2
        m.put(3, -7);          // ES256
        m.put(-1, 1);          // P-256
        m.put(-2, fixed32(pk.getW().getAffineX()));
        m.put(-3, fixed32(pk.getW().getAffineY()));
        return cbor(m);
    }

    byte[] authData(String rpId, int flags, long count, boolean attested) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(WebAuthn.sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        o.write(flags);
        o.writeBytes(ByteBuffer.allocate(4).putInt((int) count).array());
        if (attested) {
            o.writeBytes(new byte[16]); // aaguid
            o.write(credId.length >> 8);
            o.write(credId.length);
            o.writeBytes(credId);
            o.writeBytes(coseKey());
        }
        return o.toByteArray();
    }

    static byte[] clientData(String type, String challenge, String origin) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
    }

    byte[] attestation(byte[] authData) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("fmt", "none");
        m.put("attStmt", new LinkedHashMap<>());
        m.put("authData", authData);
        return cbor(m);
    }

    byte[] sign(byte[] authData, byte[] clientData) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(keys.getPrivate());
        s.update(authData);
        s.update(WebAuthn.sha256(clientData));
        return s.sign();
    }

    WebAuthn.Registration register() {
        return WebAuthn.verifyRegistration(clientData("webauthn.create", "ch1", ORIGIN),
                attestation(authData(RP, 0x45, 0, true)), "ch1", RP, ORIGINS);
    }

    @Test
    void registrationExtractsTheCredentialAndPublicKey() {
        WebAuthn.Registration r = register();
        assertArrayEquals(credId, r.credentialId());
        assertEquals(WebAuthn.ALG_ES256, r.key().alg());
        assertArrayEquals(keys.getPublic().getEncoded(), r.key().x509());
    }

    @Test
    void validAssertionIsAcceptedAndCounterReturned() throws Exception {
        WebAuthn.Registration r = register();
        byte[] ad = authData(RP, 0x05, 7, false);
        byte[] cd = clientData("webauthn.get", "ch2", ORIGIN);
        long count = WebAuthn.verifyAssertion(cd, ad, sign(ad, cd), "ch2", RP, ORIGINS, r.key(), 3);
        assertEquals(7, count);
    }

    @Test
    void syncedPasskeysWithZeroCounterAreAccepted() throws Exception {
        WebAuthn.Registration r = register();
        byte[] ad = authData(RP, 0x05, 0, false);
        byte[] cd = clientData("webauthn.get", "c", ORIGIN);
        assertEquals(0, WebAuthn.verifyAssertion(cd, ad, sign(ad, cd), "c", RP, ORIGINS, r.key(), 0));
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        WebAuthn.Registration r = register();
        byte[] ad = authData(RP, 0x05, 1, false);
        byte[] cd = clientData("webauthn.get", "c", ORIGIN);
        byte[] sig = sign(ad, cd);
        byte[] other = clientData("webauthn.get", "c", ORIGIN + "/")    ;
        assertThrows(WebAuthnException.class, () -> WebAuthn.verifyAssertion(cd, ad, sign(authData(RP, 0x05, 2, false), cd), "c", RP, ORIGINS, r.key(), 0));
        assertThrows(WebAuthnException.class, () -> WebAuthn.verifyAssertion(other, ad, sig, "c", RP, Set.of(ORIGIN, ORIGIN + "/"), r.key(), 0));
    }

    @Test
    void phishingSiteOriginIsRejected() throws Exception {
        WebAuthn.Registration r = register();
        byte[] ad = authData(RP, 0x05, 1, false);
        byte[] cd = clientData("webauthn.get", "c", "https://marketsim-login.evil.com");
        WebAuthnException e = assertThrows(WebAuthnException.class,
                () -> WebAuthn.verifyAssertion(cd, ad, sign(ad, cd), "c", RP, ORIGINS, r.key(), 0));
        assertTrue(e.getMessage().contains("Origine"));
    }

    @Test
    void credentialOfAnotherSiteIsRejected() {
        assertThrows(WebAuthnException.class, () -> WebAuthn.verifyRegistration(clientData("webauthn.create", "x", ORIGIN),
                attestation(authData("evil.com", 0x45, 0, true)), "x", RP, ORIGINS));
    }

    @Test
    void userVerificationIsMandatory() {
        WebAuthnException e = assertThrows(WebAuthnException.class, () -> WebAuthn.verifyRegistration(
                clientData("webauthn.create", "x", ORIGIN), attestation(authData(RP, 0x41, 0, true)), "x", RP, ORIGINS));
        assertTrue(e.getMessage().contains("identité"));
    }

    @Test
    void wrongChallengeOrCeremonyTypeIsRejected() {
        assertThrows(WebAuthnException.class, () -> WebAuthn.verifyRegistration(clientData("webauthn.create", "old", ORIGIN),
                attestation(authData(RP, 0x45, 0, true)), "new", RP, ORIGINS));
        assertThrows(WebAuthnException.class, () -> WebAuthn.verifyRegistration(clientData("webauthn.get", "x", ORIGIN),
                attestation(authData(RP, 0x45, 0, true)), "x", RP, ORIGINS));
    }

    @Test
    void decreasingCounterSignalsAClonedAuthenticator() throws Exception {
        WebAuthn.Registration r = register();
        byte[] ad = authData(RP, 0x05, 4, false);
        byte[] cd = clientData("webauthn.get", "c", ORIGIN);
        assertThrows(WebAuthnException.class, () -> WebAuthn.verifyAssertion(cd, ad, sign(ad, cd), "c", RP, ORIGINS, r.key(), 9));
    }

    @Test
    void cborDecoderReadsNestedValuesAndRejectsGarbage() {
        Object v = Cbor.decode(cbor(Map.of("a", List.of(1, -2, "x".getBytes(StandardCharsets.UTF_8)))));
        assertTrue(v instanceof Map<?, ?>);
        assertThrows(IllegalArgumentException.class, () -> Cbor.decode(new byte[] { (byte) 0x5F }));
        assertThrows(IllegalArgumentException.class, () -> Cbor.decode(new byte[] { 0x42, 0x01 }));
    }
}
