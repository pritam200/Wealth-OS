package com.marketai.admin.net;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * A strictly parsed IP address or CIDR range (IPv4 and IPv6). Only numeric literals are accepted, so
 * parsing never performs a DNS lookup; a hostname, a partial address or an out-of-range prefix is an
 * error. Ranges that would admit most of the internet are refused: an allowlist that says "everyone"
 * is not an allowlist.
 */
public final class Cidr {

    public static final int MIN_PREFIX_V4 = 8;
    public static final int MIN_PREFIX_V6 = 16;

    private static final Pattern V4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private final byte[] network;
    private final int prefix;

    private Cidr(byte[] network, int prefix) { this.network = network; this.prefix = prefix; }

    /** @throws IllegalArgumentException with a message safe to show to the admin */
    public static Cidr parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Enter an IP address or a CIDR range.");
        String s = text.trim();
        if (s.length() > 60) throw new IllegalArgumentException("That is too long to be an IP address or CIDR range.");
        String addr = s; Integer pfx = null;
        int slash = s.indexOf('/');
        if (slash >= 0) {
            addr = s.substring(0, slash);
            String p = s.substring(slash + 1);
            if (!p.matches("\\d{1,3}")) throw new IllegalArgumentException("The part after '/' must be a number, for example 203.0.113.0/24.");
            pfx = Integer.parseInt(p);
        }
        byte[] bytes = literal(addr);
        int bits = bytes.length * 8;
        int prefix = pfx == null ? bits : pfx;
        if (prefix > bits) throw new IllegalArgumentException("The prefix length " + prefix + " is too large for this address (maximum " + bits + ").");
        int min = bytes.length == 4 ? MIN_PREFIX_V4 : MIN_PREFIX_V6;
        if (prefix < min) throw new IllegalArgumentException("A range this wide (/" + prefix + ") would allow far too many addresses. Use /" + min + " or narrower.");
        return new Cidr(mask(bytes, prefix), prefix);
    }

    /** Parses a single address literal to its bytes (4 for IPv4, 16 for IPv6); IPv4-mapped IPv6 becomes IPv4. */
    public static byte[] literal(String addr) {
        if (addr == null) throw new IllegalArgumentException("Not an IP address.");
        String a = addr.trim();
        if (V4.matcher(a).matches()) {
            String[] p = a.split("\\.");
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(p[i]);
            return b;
        }
        if (a.indexOf(':') >= 0 && a.length() <= 45 && a.matches("[0-9a-fA-F:.]+")) {
            try {
                // A string containing ':' is an IPv6 literal, so this does no name resolution.
                return InetAddress.getByName(a).getAddress();
            } catch (Exception ignored) { /* falls through */ }
        }
        throw new IllegalArgumentException("'" + sanitize(a) + "' is not a valid IPv4 or IPv6 address.");
    }

    public boolean contains(byte[] address) {
        if (address == null || address.length != network.length) return false;
        return Arrays.equals(mask(address, prefix), network);
    }

    public boolean contains(String addressLiteral) {
        try { return contains(literal(addressLiteral)); } catch (IllegalArgumentException e) { return false; }
    }

    /** Canonical text: a single host as the bare address, a range as network/prefix. */
    public String canonical() {
        String a = text(network);
        return prefix == network.length * 8 ? a : a + "/" + prefix;
    }

    public static String text(byte[] address) {
        try { return InetAddress.getByAddress(address).getHostAddress(); } // byte-based: no DNS
        catch (Exception e) { throw new IllegalArgumentException("Not an IP address."); }
    }

    private static byte[] mask(byte[] in, int prefix) {
        byte[] out = in.clone();
        for (int i = 0; i < out.length; i++) {
            int bitsHere = Math.max(0, Math.min(8, prefix - i * 8));
            out[i] = (byte) (out[i] & (bitsHere == 0 ? 0 : (0xFF << (8 - bitsHere)) & 0xFF));
        }
        return out;
    }

    private static String sanitize(String s) {
        String t = s.replaceAll("[^\\x20-\\x7E]", "?");
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    @Override public String toString() { return canonical(); }
}
