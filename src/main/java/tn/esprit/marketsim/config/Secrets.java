package tn.esprit.marketsim.config;

import java.security.SecureRandom;
import java.util.Base64;

/** Generation des secrets de repli quand la variable d'environnement correspondante est absente. */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Sans caracteres ambigus (0/O, 1/l/I) : le mot de passe est recopie a la main depuis les logs. */
    private static final String PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private Secrets() {
    }

    /** Secret HMAC aleatoire de 48 octets (384 bits), encode en Base64. */
    public static String randomJwtSecret() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Mot de passe aleatoire de {@code length} caracteres, avec au moins une lettre et un chiffre. */
    public static String randomPassword(int length) {
        while (true) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
            String p = sb.toString();
            if (p.chars().anyMatch(Character::isDigit) && p.chars().anyMatch(Character::isLetter)) return p;
        }
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
