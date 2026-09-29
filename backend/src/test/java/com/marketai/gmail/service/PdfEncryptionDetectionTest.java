package com.marketai.gmail.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a statement needs a password before one is looked for. An unencrypted PDF used to
 * wait in PASSWORD_FAILED for a password that didn't exist.
 */
class PdfEncryptionDetectionTest {

    private static byte[] pdf(String userPassword, String ownerPassword) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            if (ownerPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("a plain PDF opens without a password")
    void plainPdfIsNotEncrypted() throws Exception {
        assertThat(PdfImportService.isEncrypted(pdf(null, null))).isFalse();
    }

    @Test
    @DisplayName("a PDF locked with a user password needs one")
    void userPasswordPdfIsEncrypted() throws Exception {
        assertThat(PdfImportService.isEncrypted(pdf("ABCDE1234F", "owner"))).isTrue();
    }

    @Test
    @DisplayName("a PDF with only an owner (permissions) password still opens without one")
    void ownerOnlyPdfOpens() throws Exception {
        assertThat(PdfImportService.isEncrypted(pdf("", "owner"))).isFalse();
    }

    @Test
    @DisplayName("a corrupted file is not mistaken for an encrypted one")
    void corruptFileIsNotEncrypted() {
        assertThat(PdfImportService.isEncrypted("not a pdf".getBytes())).isFalse();
    }
}
