package com.marketai.gmail.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AES-GCM at-rest encryption for sensitive string columns (Gmail OAuth tokens).
 *
 * <p>Plaintext values written before this converter existed still decrypt-fail; those are
 * returned as-is and re-encrypted the next time the entity is saved, so a deploy does not lock
 * every connected mailbox out.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static PasswordCipher cipher;

    @Autowired
    void setCipher(PasswordCipher passwordCipher) {
        EncryptedStringConverter.cipher = passwordCipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) return attribute;
        if (cipher == null) return attribute;
        // Already encrypted blobs start with a base64 IV+ciphertext that decrypts cleanly;
        // re-encrypting them would nest ciphertexts. Detect by attempting decrypt.
        if (looksEncrypted(attribute)) return attribute;
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || cipher == null) return dbData;
        try {
            return cipher.decrypt(dbData);
        } catch (RuntimeException e) {
            // Legacy plaintext row — use as-is until the next write re-encrypts it.
            return dbData;
        }
    }

    private boolean looksEncrypted(String value) {
        try {
            cipher.decrypt(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
