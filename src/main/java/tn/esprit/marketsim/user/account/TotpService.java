package tn.esprit.marketsim.user.account;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * Double authentification par code a 6 chiffres (TOTP, RFC 6238 : HMAC-SHA1, pas de 30 s),
 * compatible avec Google Authenticator, Microsoft Authenticator, Authy...
 */
@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final String ISSUER = "MarketSim AI";
    private final SecureRandom random = new SecureRandom();

    /** Nouveau secret aleatoire de 160 bits, encode en base32 (32 caracteres). */
    public String newSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32(bytes);
    }

    /** Lien a afficher en QR code dans l'application d'authentification. */
    public String otpauthUri(String secret, String email) {
        String label = URLEncoder.encode(ISSUER + ":" + email, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer="
                + URLEncoder.encode(ISSUER, StandardCharsets.UTF_8).replace("+", "%20") + "&digits=6&period=30";
    }

    /** Accepte le code du pas courant et des pas voisins (decalage d'horloge de +/- 30 s). */
    public boolean verify(String secret, String code, long epochSeconds) {
        if (secret == null || code == null) return false;
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) return false;
        long counter = epochSeconds / STEP_SECONDS;
        for (long c = counter - 1; c <= counter + 1; c++) {
            if (code(secret, c).equals(digits)) return true;
        }
        return false;
    }

    public boolean verify(String secret, String code) {
        return verify(secret, code, System.currentTimeMillis() / 1000);
    }

    /** Code a 6 chiffres pour un compteur donne (expose pour les tests). */
    static String code(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = h[h.length - 1] & 0x0f;
            int bin = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8)
                    | (h[offset + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] decodeBase32(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char ch : clean.toCharArray()) {
            int v = BASE32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("Secret base32 invalide");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }
}
