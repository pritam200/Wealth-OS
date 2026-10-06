package com.marketai.admin.net;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * The one place that decides who the caller is. The TCP peer address is the only thing a client
 * cannot forge, so {@code X-Forwarded-For} is believed only when the peer is one of the configured
 * trusted proxies ({@code app.admin.trusted-proxies}; empty means the header is ignored). The header
 * is read right to left, skipping trusted proxies; the first address that is not a trusted proxy is
 * the client. Anything malformed, or a chain made up only of proxies, gives "unknown" and the caller
 * is denied.
 */
@Component
public class ClientIpResolver {

    private static final int MAX_HOPS = 20;
    private final List<Cidr> trusted;

    @Autowired
    public ClientIpResolver(@Value("${app.admin.trusted-proxies:}") String trustedProxies) {
        this.trusted = Arrays.stream(trustedProxies.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(Cidr::parse)
            .toList();
    }

    /** Test/explicit constructor. */
    public ClientIpResolver(List<String> trustedProxyCidrs) {
        this.trusted = trustedProxyCidrs.stream().map(Cidr::parse).toList();
    }

    public Optional<String> resolve(HttpServletRequest request) {
        byte[] peer;
        try { peer = Cidr.literal(request.getRemoteAddr()); } catch (RuntimeException e) { return Optional.empty(); }
        if (!isTrusted(peer)) return Optional.of(Cidr.text(peer));   // header, if any, is attacker-controlled

        List<String> hops = forwardedFor(request);
        if (hops.isEmpty()) return Optional.of(Cidr.text(peer));     // the proxy itself is calling
        if (hops.size() > MAX_HOPS) return Optional.empty();
        List<byte[]> parsed = new ArrayList<>();
        for (String h : hops) {
            try { parsed.add(Cidr.literal(h)); } catch (RuntimeException e) { return Optional.empty(); }
        }
        for (int i = parsed.size() - 1; i >= 0; i--) {
            if (!isTrusted(parsed.get(i))) return Optional.of(Cidr.text(parsed.get(i)));
        }
        return Optional.empty();
    }

    private boolean isTrusted(byte[] address) {
        for (Cidr c : trusted) if (c.contains(address)) return true;
        return false;
    }

    private static List<String> forwardedFor(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("X-Forwarded-For");
        if (values == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String v : Collections.list(values)) {
            for (String part : v.split(",", -1)) {
                String p = part.trim();
                out.add(p);   // an empty element stays and fails parsing later: do not guess
            }
        }
        return out.size() == 1 && out.get(0).isEmpty() ? List.of() : out;
    }
}
