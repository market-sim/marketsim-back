package tn.esprit.marketsim.user.passkey;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verification WebAuthn (passkeys : Face ID, Touch ID, empreinte, Windows Hello, cle de securite),
 * ecrite avec la seule bibliotheque standard Java. Logique pure, sans Spring : testee seule.
 *
 * <p>Ce qui est verifie, a l'enregistrement comme a la connexion :</p>
 * <ul>
 *   <li>clientDataJSON : type attendu, defi (challenge) emis par le serveur, origine autorisee ;</li>
 *   <li>authenticatorData : empreinte SHA-256 de l'identifiant du site (rpId), presence de l'utilisateur (UP)
 *       et verification de l'utilisateur (UV = visage, doigt ou code de l'appareil) ;</li>
 *   <li>connexion : signature de authenticatorData || SHA-256(clientDataJSON) avec la cle publique enregistree,
 *       et compteur de signatures croissant (detection d'un authentificateur clone).</li>
 * </ul>
 * <p>L'attestation du fabricant n'est pas exigee (format « none ») : c'est le choix courant des sites grand public.</p>
 */
public final class WebAuthn {

    private WebAuthn() {
    }

    static final int FLAG_UP = 0x01;
    static final int FLAG_UV = 0x04;
    static final int FLAG_AT = 0x40;

    public static final long ALG_ES256 = -7;
    public static final long ALG_RS256 = -257;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();

    public static class WebAuthnException extends IllegalArgumentException {
        public WebAuthnException(String message) {
            super(message);
        }
    }

    public static String b64url(byte[] bytes) {
        return B64URL_ENC.encodeToString(bytes);
    }

    public static byte[] fromB64url(String s) {
        try {
            return B64URL.decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
        } catch (IllegalArgumentException e) {
            throw new WebAuthnException("Donnée base64url invalide");
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cle publique d'une passkey, au format X.509 (stockable en base) + algorithme COSE. */
    public record StoredKey(byte[] x509, long alg) {
    }

    /** Resultat d'un enregistrement valide. */
    public record Registration(byte[] credentialId, StoredKey key, long signCount, boolean backedUp) {
    }

    /** Donnees de l'authentificateur decodees. */
    record AuthData(byte[] rpIdHash, int flags, long signCount, byte[] credentialId, Map<?, ?> cosePublicKey) {
    }

    // ─── clientDataJSON ───────────────────────────────────────────────────────────────────────

    static void checkClientData(byte[] clientDataJson, String expectedType, String expectedChallenge, Set<String> origins) {
        JsonNode node;
        try {
            node = JSON.readTree(new String(clientDataJson, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new WebAuthnException("clientDataJSON illisible");
        }
        if (!expectedType.equals(node.path("type").asText())) throw new WebAuthnException("Type de cérémonie inattendu");
        // comparaison en temps constant du defi
        byte[] got = node.path("challenge").asText().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(got, expectedChallenge.getBytes(StandardCharsets.UTF_8))) {
            throw new WebAuthnException("Défi invalide ou expiré");
        }
        String origin = node.path("origin").asText();
        if (!origins.contains(origin)) throw new WebAuthnException("Origine non autorisée : " + origin);
        if (node.path("crossOrigin").asBoolean(false)) throw new WebAuthnException("Requête depuis un autre site refusée");
    }

    // ─── authenticatorData ────────────────────────────────────────────────────────────────────

    static AuthData parseAuthData(byte[] a) {
        if (a.length < 37) throw new WebAuthnException("authenticatorData trop court");
        byte[] rpIdHash = Arrays.copyOfRange(a, 0, 32);
        int flags = a[32] & 0xFF;
        long count = ByteBuffer.wrap(a, 33, 4).getInt() & 0xFFFFFFFFL;
        if ((flags & FLAG_AT) == 0) return new AuthData(rpIdHash, flags, count, null, null);
        if (a.length < 55) throw new WebAuthnException("Données d'identifiant manquantes");
        int idLen = ((a[53] & 0xFF) << 8) | (a[54] & 0xFF);
        if (idLen == 0 || idLen > 1023 || 55 + idLen > a.length) throw new WebAuthnException("Identifiant de passkey invalide");
        byte[] credId = Arrays.copyOfRange(a, 55, 55 + idLen);
        Cbor.Decoded key = Cbor.decodeAt(a, 55 + idLen);
        if (!(key.value() instanceof Map<?, ?> map)) throw new WebAuthnException("Clé publique COSE invalide");
        return new AuthData(rpIdHash, flags, count, credId, map);
    }

    static void checkAuthData(AuthData d, String rpId) {
        if (!MessageDigest.isEqual(d.rpIdHash(), sha256(rpId.getBytes(StandardCharsets.UTF_8)))) {
            throw new WebAuthnException("Cette passkey appartient à un autre site");
        }
        if ((d.flags() & FLAG_UP) == 0) throw new WebAuthnException("Présence de l'utilisateur non confirmée");
        if ((d.flags() & FLAG_UV) == 0) {
            throw new WebAuthnException("L'appareil n'a pas vérifié ton identité (visage, empreinte ou code)");
        }
    }

    // ─── cle COSE -> cle Java ─────────────────────────────────────────────────────────────────

    static StoredKey coseToKey(Map<?, ?> cose) {
        long kty = num(cose.get(1L));
        long alg = num(cose.get(3L));
        try {
            if (kty == 2 && alg == ALG_ES256) {
                if (num(cose.get(-1L)) != 1) throw new WebAuthnException("Courbe elliptique non supportée");
                byte[] x = (byte[]) cose.get(-2L);
                byte[] y = (byte[]) cose.get(-3L);
                if (x == null || y == null || x.length != 32 || y.length != 32) throw new WebAuthnException("Coordonnées EC invalides");
                AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                params.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
                PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                        new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
                return new StoredKey(key.getEncoded(), alg);
            }
            if (kty == 3 && alg == ALG_RS256) {
                byte[] n = (byte[]) cose.get(-1L);
                byte[] e = (byte[]) cose.get(-2L);
                if (n == null || e == null || n.length < 256) throw new WebAuthnException("Clé RSA invalide ou trop courte");
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
                return new StoredKey(key.getEncoded(), alg);
            }
        } catch (GeneralSecurityException | ClassCastException ex) {
            throw new WebAuthnException("Clé publique illisible");
        }
        throw new WebAuthnException("Algorithme de passkey non supporté (" + alg + ")");
    }

    private static long num(Object o) {
        if (o instanceof Long l) return l;
        throw new WebAuthnException("Clé COSE incomplète");
    }

    // ─── ceremonies ───────────────────────────────────────────────────────────────────────────

    /** Enregistrement d'une nouvelle passkey (navigator.credentials.create). */
    public static Registration verifyRegistration(byte[] clientDataJson, byte[] attestationObject, String expectedChallenge,
                                                  String rpId, Set<String> origins) {
        checkClientData(clientDataJson, "webauthn.create", expectedChallenge, origins);
        Object att = Cbor.decode(attestationObject);
        if (!(att instanceof Map<?, ?> map) || !(map.get("authData") instanceof byte[] authData)) {
            throw new WebAuthnException("Objet d'attestation invalide");
        }
        AuthData d = parseAuthData(authData);
        checkAuthData(d, rpId);
        if (d.credentialId() == null) throw new WebAuthnException("Aucune passkey créée");
        StoredKey key = coseToKey(d.cosePublicKey());
        boolean backedUp = (d.flags() & 0x10) != 0; // BS : synchronisee (iCloud, Google...)
        return new Registration(d.credentialId(), key, d.signCount(), backedUp);
    }

    /**
     * Connexion avec une passkey (navigator.credentials.get).
     *
     * @return le nouveau compteur de signatures a enregistrer
     */
    public static long verifyAssertion(byte[] clientDataJson, byte[] authenticatorData, byte[] signature, String expectedChallenge,
                                       String rpId, Set<String> origins, StoredKey key, long storedCount) {
        checkClientData(clientDataJson, "webauthn.get", expectedChallenge, origins);
        AuthData d = parseAuthData(authenticatorData);
        checkAuthData(d, rpId);
        byte[] signed = ByteBuffer.allocate(authenticatorData.length + 32)
                .put(authenticatorData).put(sha256(clientDataJson)).array();
        try {
            String algo = key.alg() == ALG_ES256 ? "SHA256withECDSA" : "SHA256withRSA";
            String kf = key.alg() == ALG_ES256 ? "EC" : "RSA";
            PublicKey pk = KeyFactory.getInstance(kf).generatePublic(new X509EncodedKeySpec(key.x509()));
            Signature s = Signature.getInstance(algo);
            s.initVerify(pk);
            s.update(signed);
            if (!s.verify(signature)) throw new WebAuthnException("Signature de la passkey invalide");
        } catch (GeneralSecurityException e) {
            throw new WebAuthnException("Signature de la passkey invalide");
        }
        // compteur : s'il est utilise, il doit toujours augmenter (sinon la passkey a peut-etre ete copiee)
        if ((d.signCount() != 0 || storedCount != 0) && d.signCount() <= storedCount) {
            throw new WebAuthnException("Compteur de la passkey incohérent : appareil peut-être cloné");
        }
        return d.signCount();
    }
}
