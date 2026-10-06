package com.marketai.admin.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/** RFC 6238 time-based one-time passwords (HMAC-SHA1, 6 digits, 30 s) with RFC 4648 base32 secrets. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    public static final int STEP_SECONDS = 30;

    private Totp() {}

    public static String newSecret() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        return base32(b);
    }

    public static long counter(long epochSeconds) { return epochSeconds / STEP_SECONDS; }

    public static String code(String base32Secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(base32Secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int o = h[h.length - 1] & 0x0F;
            int bin = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** The matching counter within ±1 step that is newer than {@code lastUsed}, or -1. Constant-time compare per candidate. */
    public static long verify(String base32Secret, String submitted, long nowCounter, long lastUsed) {
        if (submitted == null || !submitted.matches("\\d{6}")) return -1;
        for (long c = nowCounter - 1; c <= nowCounter + 1; c++) {
            if (c > lastUsed && java.security.MessageDigest.isEqual(code(base32Secret, c).getBytes(), submitted.getBytes())) return c;
        }
        return -1;
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : data) {
            buffer = (buffer << 8) | (x & 0xFF); bits += 8;
            while (bits >= 5) { sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] decode(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char ch : s.toUpperCase().toCharArray()) {
            int v = ALPHABET.indexOf(ch);
            if (v < 0) continue;
            buffer = (buffer << 5) | v; bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xFF); bits -= 8; }
        }
        return out.toByteArray();
    }
}
