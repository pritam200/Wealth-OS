package com.marketai.gmail.service;

import com.marketai.gmail.entity.PendingPdf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PdfRetryWaitTest {

    private static PendingPdf pdf(String status, LocalDateTime lastRead) {
        PendingPdf p = new PendingPdf();
        p.setStatus(status);
        p.setUnlockedAt(lastRead);
        return p;
    }

    @Test
    @DisplayName("a read that did not finish waits six hours before the model is asked again; locked PDFs are tried every sync")
    void failedReadsWait() {
        LocalDateTime now = LocalDateTime.now();
        assertThat(PdfImportService.dueRetry(pdf("FAILED", now.minusMinutes(30)))).isFalse();
        assertThat(PdfImportService.dueRetry(pdf("FAILED", now.minusHours(7)))).isTrue();
        assertThat(PdfImportService.dueRetry(pdf("FAILED", null))).isTrue();
        assertThat(PdfImportService.dueRetry(pdf("PASSWORD_FAILED", now.minusMinutes(5)))).isTrue();
    }
}
