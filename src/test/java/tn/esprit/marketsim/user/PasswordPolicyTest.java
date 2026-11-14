package tn.esprit.marketsim.user;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void needsEightCharactersWithALetterAndADigit() {
        assertNotNull(PasswordPolicy.problem("abc123"), "trop court");
        assertNotNull(PasswordPolicy.problem("abcdefgh"), "pas de chiffre");
        assertNotNull(PasswordPolicy.problem("12345678"), "pas de lettre");
        assertNotNull(PasswordPolicy.problem(null));
        assertNull(PasswordPolicy.problem("admin123"));
        assertNull(PasswordPolicy.problem("Marché2026"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.check("secret"));
        assertDoesNotThrow(() -> PasswordPolicy.check("secret12"));
    }
}
