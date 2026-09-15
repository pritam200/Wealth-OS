package com.marketai.gmail.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attachment-level deduplication by content, not by filename or attachment id.
 *
 * The existing unique constraint is (user, gmail_message_id, attachment_id), which only catches
 * the same attachment on the same message. It misses the cases that actually recur: a forwarded
 * statement (new message id), a provider re-send, the same file on two threads. Gmail is also
 * documented to hand back a different attachment_id for the same physical file across fetches —
 * which is why the lookup code had already been changed to match on filename instead, and
 * filename is no better, because providers name every month's statement "Statement.pdf".
 */
class DocumentFingerprintTest {

    private static byte[] pdf(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("identical bytes hash identically, whatever the filename or message id")
    void identicalContentHashesIdentically() {
        byte[] original = pdf("%PDF-1.4 contract note RELIANCE 25 @ 1412.50");
        byte[] forwarded = pdf("%PDF-1.4 contract note RELIANCE 25 @ 1412.50");

        assertThat(PdfImportService.sha256(original))
            .isEqualTo(PdfImportService.sha256(forwarded));
    }

    @Test
    @DisplayName("a different statement hashes differently")
    void differentContentHashesDifferently() {
        assertThat(PdfImportService.sha256(pdf("statement for September")))
            .isNotEqualTo(PdfImportService.sha256(pdf("statement for October")));
    }

    @Test
    @DisplayName("a single changed byte changes the hash")
    void hashIsSensitiveToSmallChanges() {
        // Quantity 25 vs 26 is a different trade; the fingerprint must not collapse them.
        assertThat(PdfImportService.sha256(pdf("RELIANCE 25 @ 1412.50")))
            .isNotEqualTo(PdfImportService.sha256(pdf("RELIANCE 26 @ 1412.50")));
    }

    @Test
    void hashIsAStableLowercaseHex64() {
        String hash = PdfImportService.sha256(pdf("anything"));

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        // Deterministic across calls — a fingerprint that varied would defeat the whole point.
        assertThat(hash).isEqualTo(PdfImportService.sha256(pdf("anything")));
    }

    @Test
    void emptyDocumentStillHashes() {
        assertThat(PdfImportService.sha256(new byte[0])).hasSize(64);
    }
}
