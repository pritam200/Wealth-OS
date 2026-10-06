package com.marketai.admin;

import com.marketai.admin.mfa.Totp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
    // RFC 6238 appendix B: ASCII secret "12345678901234567890"; the 8-digit codes end in the 6-digit ones below.
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test void matchesRfcVectors() {
        assertEquals("287082", Totp.code(SECRET, Totp.counter(59)));
        assertEquals("081804", Totp.code(SECRET, Totp.counter(1111111109L)));
        assertEquals("050471", Totp.code(SECRET, Totp.counter(1111111111L)));
        assertEquals("005924", Totp.code(SECRET, Totp.counter(1234567890L)));
    }

    @Test void acceptsAdjacentStepsOnly() {
        long now = Totp.counter(1111111109L);
        assertTrue(Totp.verify(SECRET, Totp.code(SECRET, now - 1), now, 0) > 0);
        assertTrue(Totp.verify(SECRET, Totp.code(SECRET, now + 1), now, 0) > 0);
        assertEquals(-1, Totp.verify(SECRET, Totp.code(SECRET, now - 2), now, 0));
    }

    @Test void aCodeCannotBeReplayed() {
        long now = Totp.counter(1111111109L);
        long used = Totp.verify(SECRET, Totp.code(SECRET, now), now, 0);
        assertTrue(used > 0);
        assertEquals(-1, Totp.verify(SECRET, Totp.code(SECRET, now), now, used));
    }

    @Test void rejectsMalformedCodes() {
        for (String bad : new String[]{null, "", "12345", "1234567", "abcdef", "12 456"}) assertEquals(-1, Totp.verify(SECRET, bad, 1, 0));
    }

    @Test void generatedSecretsAreUsable() {
        String s = Totp.newSecret();
        assertEquals(32, s.length());
        assertNotEquals(s, Totp.newSecret());
        assertEquals(6, Totp.code(s, 5).length());
    }
}
