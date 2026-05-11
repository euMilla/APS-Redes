package aps.shared.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void hashesAndVerifiesPassword() {
        String hash = PasswordHasher.hash("membro123");

        assertTrue(PasswordHasher.isHash(hash));
        assertTrue(PasswordHasher.verify("membro123", hash));
        assertFalse(PasswordHasher.verify("senha-errada", hash));
    }

    @Test
    void usesDifferentSaltForEachHash() {
        String first = PasswordHasher.hash("admin123");
        String second = PasswordHasher.hash("admin123");

        assertNotEquals(first, second);
        assertTrue(PasswordHasher.verify("admin123", first));
        assertTrue(PasswordHasher.verify("admin123", second));
    }
}
