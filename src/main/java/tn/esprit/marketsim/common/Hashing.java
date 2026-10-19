package tn.esprit.marketsim.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Empreintes SHA-256 partagees (jeux de donnees reels, journal d'audit). */
public final class Hashing {

    private Hashing() {
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
