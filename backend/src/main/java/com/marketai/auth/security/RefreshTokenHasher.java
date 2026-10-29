package com.marketai.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way hash for refresh tokens at rest. A database read must not yield a usable bearer
 * credential — only the client holds the plaintext UUID.
 */
public final class RefreshTokenHasher {

    private RefreshTokenHasher() {}

    public static String hash(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }

    /** Legacy rows stored the UUID verbatim (36 chars); hashed rows are 64 hex chars. */
    public static boolean looksHashed(String stored) {
        return stored != null && stored.length() == 64 && stored.chars().allMatch(c ->
            (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
}
