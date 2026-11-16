package tn.esprit.marketsim.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.security.JwtService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;

class SecretsTest {

    @Test
    void secretJwtAleatoireDe384Bits() {
        String a = Secrets.randomJwtSecret();
        assertEquals(48, Base64.getDecoder().decode(a).length);
        assertNotEquals(a, Secrets.randomJwtSecret());
    }

    @Test
    void motDePasseAleatoireAvecLettresEtChiffres() {
        for (int i = 0; i < 200; i++) {
            String p = Secrets.randomPassword(16);
            assertEquals(16, p.length());
            assertTrue(p.chars().anyMatch(Character::isDigit));
            assertTrue(p.chars().anyMatch(Character::isLetter));
        }
    }

    @Test
    void sansJwtSecretLeServiceGenereUnSecretEtSigneQuandMeme() {
        JwtService jwt = new JwtService("", 60_000);
        User u = User.builder().email("a@b.tn").role(Role.USER).build();
        String token = jwt.generateToken(u);
        assertEquals("a@b.tn", jwt.extractEmail(token));
        // une autre instance (= un redemarrage) a un autre secret
        JwtService other = new JwtService(null, 60_000);
        assertThrows(io.jsonwebtoken.JwtException.class, () -> other.parse(token));
    }
}
