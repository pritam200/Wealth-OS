package com.marketai.admin;

import com.marketai.admin.net.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "172.16.0.0/12"));

    private Optional<String> resolve(ClientIpResolver r, String peer, String... xff) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(peer);
        for (String h : xff) req.addHeader("X-Forwarded-For", h);
        return r.resolve(req);
    }

    @Test void directCallerUsesPeer() {
        assertEquals(Optional.of("203.0.113.5"), resolve(resolver, "203.0.113.5"));
    }

    @Test void headerFromUntrustedPeerIsIgnored() {
        // The attacker claims to be an allowlisted address.
        assertEquals(Optional.of("198.51.100.9"), resolve(resolver, "198.51.100.9", "203.0.113.10"));
    }

    @Test void headerFromTrustedProxyIsBelieved() {
        assertEquals(Optional.of("203.0.113.5"), resolve(resolver, "10.1.2.3", "203.0.113.5"));
    }

    @Test void spoofedLeftmostEntryDoesNotWin() {
        // client sent "X-Forwarded-For: 203.0.113.10"; the proxy appended the real address.
        assertEquals(Optional.of("198.51.100.9"), resolve(resolver, "10.1.2.3", "203.0.113.10, 198.51.100.9"));
    }

    @Test void chainOfTrustedProxiesIsSkippedRightToLeft() {
        assertEquals(Optional.of("203.0.113.5"), resolve(resolver, "10.1.2.3", "203.0.113.5, 172.20.0.2, 10.9.9.9"));
    }

    @Test void multipleHeaderLinesAreCombined() {
        assertEquals(Optional.of("198.51.100.9"), resolve(resolver, "10.1.2.3", "203.0.113.10", "198.51.100.9"));
    }

    @Test void malformedHeaderDenies() {
        assertEquals(Optional.empty(), resolve(resolver, "10.1.2.3", "203.0.113.5, not-an-ip"));
        assertEquals(Optional.empty(), resolve(resolver, "10.1.2.3", "203.0.113.5,,"));
        assertEquals(Optional.empty(), resolve(resolver, "10.1.2.3", "evil.example.com"));
    }

    @Test void chainOnlyOfProxiesDenies() {
        assertEquals(Optional.empty(), resolve(resolver, "10.1.2.3", "10.5.5.5, 172.16.0.9"));
    }

    @Test void trustedProxyWithoutHeaderIsItself() {
        assertEquals(Optional.of("10.1.2.3"), resolve(resolver, "10.1.2.3"));
    }

    @Test void noTrustedProxiesMeansHeaderNeverCounts() {
        ClientIpResolver none = new ClientIpResolver("");
        assertEquals(Optional.of("10.1.2.3"), resolve(none, "10.1.2.3", "203.0.113.10"));
    }

    @Test void unparseablePeerDenies() {
        assertEquals(Optional.empty(), resolve(resolver, "garbage"));
    }

    @Test void ipv6ClientBehindProxy() {
        assertEquals(Optional.of("2001:db8:0:0:0:0:0:1"), resolve(resolver, "10.1.2.3", "2001:db8::1"));
    }
}
