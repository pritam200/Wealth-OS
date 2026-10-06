package com.marketai.admin;

import com.marketai.admin.net.Cidr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CidrTest {

    @Test void singleIpv4() {
        Cidr c = Cidr.parse("203.0.113.10");
        assertTrue(c.contains("203.0.113.10"));
        assertFalse(c.contains("203.0.113.11"));
        assertEquals("203.0.113.10", c.canonical());
    }

    @Test void rangeIsNormalisedAndMatches() {
        Cidr c = Cidr.parse("203.0.113.77/24");
        assertEquals("203.0.113.0/24", c.canonical());
        assertTrue(c.contains("203.0.113.255"));
        assertFalse(c.contains("203.0.114.1"));
    }

    @Test void nonByteAlignedPrefix() {
        Cidr c = Cidr.parse("10.0.0.0/20");
        assertTrue(c.contains("10.0.15.255"));
        assertFalse(c.contains("10.0.16.0"));
    }

    @Test void ipv6() {
        Cidr c = Cidr.parse("2001:db8::/32");
        assertTrue(c.contains("2001:db8:ffff::1"));
        assertFalse(c.contains("2001:db9::1"));
        assertFalse(c.contains("203.0.113.1"), "families never match each other");
    }

    @Test void rejectsHostnamesAndGarbage() {
        for (String bad : new String[]{"example.com", "localhost", "999.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4", "", "  ",
                "1.2.3.4/abc", "1.2.3.4/33", "::1/129", "1.2.3.4/-1", "1.2.3.4/", "http://1.2.3.4", "1.2.3.4 ; DROP", null}) {
            assertThrows(IllegalArgumentException.class, () -> Cidr.parse(bad), String.valueOf(bad));
        }
    }

    @Test void refusesRangesThatAllowTheWorld() {
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("0.0.0.0/0"));
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/7"));
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("::/0"));
        assertDoesNotThrow(() -> Cidr.parse("10.0.0.0/8"));
    }

    @Test void containsIsFalseForGarbage() {
        assertFalse(Cidr.parse("10.0.0.0/8").contains("not-an-ip"));
        assertFalse(Cidr.parse("10.0.0.0/8").contains((String) null));
    }
}
