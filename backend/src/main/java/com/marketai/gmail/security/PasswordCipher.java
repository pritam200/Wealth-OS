package com.marketai.gmail.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256/GCM encryption for statement-unlock passwords saved by {@code PdfImportService}.
 * The key comes from PDF_PASSWORD_ENC_KEY (base64, 32 bytes) — set it in gmail.env alongside
 * the Gmail OAuth secrets. If unset, a random key is generated at startup instead of failing
 * to boot: convenient for local dev, but it means anything saved before a restart becomes
 * undecryptable after one — acceptable there, but PDF_PASSWORD_ENC_KEY must be set and stable
 * in any environment where saved passwords need to survive a restart.
 */
@Component
public class PasswordCipher {
    private static final Logger log = LoggerFactory.getLogger(PasswordCipher.class);
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_LEN_BITS = 128;

    @Value("${app.security.pdf-password-key:}")
    private String configuredKeyB64;

    private SecretKey key;

    @PostConstruct
    void init() {
        // Spring @Value only reads env vars / application.yml — the key may live
        // in the gitignored gmail.env instead, so check there too.
        if (configuredKeyB64 == null || configuredKeyB64.trim().isEmpty()) {
            configuredKeyB64 = loadFromEnvFile("PDF_PASSWORD_ENC_KEY");
        }
        if (configuredKeyB64 != null && !configuredKeyB64.trim().isEmpty()) {
            byte[] raw = Base64.getDecoder().decode(configuredKeyB64.trim());
            key = new SecretKeySpec(raw, "AES");
            return;
        }
        // No key configured — generate one and persist it to gmail.env so saved
        // passwords survive backend restarts without the user having to manually
        // create and set PDF_PASSWORD_ENC_KEY.
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            key = kg.generateKey();
            String b64 = Base64.getEncoder().encodeToString(key.getEncoded());
            persistKeyToEnvFile(b64);
            log.info("Generated and saved PDF_PASSWORD_ENC_KEY to gmail.env — passwords will survive restarts.");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate AES key", e);
        }
    }

    private String loadFromEnvFile(String key) {
        java.io.File envFile = new java.io.File("gmail.env");
        if (!envFile.exists()) envFile = new java.io.File("backend/gmail.env");
        if (!envFile.exists()) return null;
        try {
            for (String line : java.nio.file.Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + "=")) {
                    String val = trimmed.substring(key.length() + 1).trim();
                    return val.isEmpty() ? null : val;
                }
            }
        } catch (Exception e) { log.debug("Could not read gmail.env: {}", e.getMessage()); }
        return null;
    }

    private void persistKeyToEnvFile(String keyB64) {
        java.io.File envFile = new java.io.File("gmail.env");
        if (!envFile.exists()) envFile = new java.io.File("backend/gmail.env");
        if (!envFile.exists()) {
            log.warn("No gmail.env found — PDF_PASSWORD_ENC_KEY generated but not persisted. " +
                     "Saved passwords will not survive a restart.");
            return;
        }
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(envFile.toPath(), StandardCharsets.UTF_8);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("PDF_PASSWORD_ENC_KEY=")) {
                    lines.set(i, "PDF_PASSWORD_ENC_KEY=" + keyB64);
                    found = true;
                    break;
                }
            }
            if (!found) lines.add("PDF_PASSWORD_ENC_KEY=" + keyB64);
            java.nio.file.Files.write(envFile.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not write PDF_PASSWORD_ENC_KEY to {}: {}", envFile.getPath(), e.getMessage());
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Password encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            byte[] ciphertext = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            System.arraycopy(combined, IV_LEN, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Password decryption failed — the encryption key may have changed", e);
        }
    }
}
