package tn.esprit.marketsim.user.passkey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodeur CBOR minimal (RFC 8949) pour lire les donnees WebAuthn : objet d'attestation et cle publique COSE.
 * Prend en charge les entiers, chaines d'octets, textes, tableaux, maps et valeurs simples ; c'est tout ce
 * qu'utilisent les authentificateurs (Face ID, Touch ID, Windows Hello, cles de securite).
 */
public final class Cbor {

    private final byte[] data;
    private int pos;

    private Cbor(byte[] data, int offset) {
        this.data = data;
        this.pos = offset;
    }

    /** Decode une valeur complete ; erreur si des octets restent apres. */
    public static Object decode(byte[] data) {
        Cbor c = new Cbor(data, 0);
        Object v = c.read(0);
        if (c.pos != data.length) throw new IllegalArgumentException("CBOR : octets en trop");
        return v;
    }

    /** Decode une valeur a partir d'une position et renvoie aussi la position de fin (donnees qui suivent). */
    public static Decoded decodeAt(byte[] data, int offset) {
        Cbor c = new Cbor(data, offset);
        Object v = c.read(0);
        return new Decoded(v, c.pos);
    }

    public record Decoded(Object value, int end) {
    }

    private int u8() {
        if (pos >= data.length) throw new IllegalArgumentException("CBOR tronque");
        return data[pos++] & 0xFF;
    }

    private long argument(int info) {
        if (info < 24) return info;
        int bytes = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new IllegalArgumentException("CBOR : longueur indefinie non supportee");
        };
        long v = 0;
        for (int i = 0; i < bytes; i++) v = (v << 8) | u8();
        if (v < 0) throw new IllegalArgumentException("CBOR : entier trop grand");
        return v;
    }

    private byte[] bytes(long len) {
        if (len > data.length - pos) throw new IllegalArgumentException("CBOR tronque");
        byte[] out = new byte[(int) len];
        System.arraycopy(data, pos, out, 0, out.length);
        pos += out.length;
        return out;
    }

    private Object read(int depth) {
        if (depth > 16) throw new IllegalArgumentException("CBOR trop imbrique");
        int head = u8();
        int major = head >> 5;
        int info = head & 0x1F;
        switch (major) {
            case 0:
                return argument(info);
            case 1:
                return -1 - argument(info);
            case 2:
                return bytes(argument(info));
            case 3:
                return new String(bytes(argument(info)), StandardCharsets.UTF_8);
            case 4: {
                long n = argument(info);
                if (n > 1000) throw new IllegalArgumentException("CBOR : tableau trop grand");
                List<Object> list = new ArrayList<>();
                for (long i = 0; i < n; i++) list.add(read(depth + 1));
                return list;
            }
            case 5: {
                long n = argument(info);
                if (n > 1000) throw new IllegalArgumentException("CBOR : map trop grande");
                Map<Object, Object> map = new LinkedHashMap<>();
                for (long i = 0; i < n; i++) {
                    Object k = read(depth + 1);
                    map.put(k, read(depth + 1));
                }
                return map;
            }
            case 6:
                argument(info); // etiquette ignoree
                return read(depth + 1);
            case 7:
                return switch (info) {
                    case 20 -> Boolean.FALSE;
                    case 21 -> Boolean.TRUE;
                    case 22, 23 -> null;
                    default -> throw new IllegalArgumentException("CBOR : valeur simple non supportee");
                };
            default:
                throw new IllegalArgumentException("CBOR invalide");
        }
    }
}
