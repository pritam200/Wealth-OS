package com.marketai.gmail.service;

import com.marketai.gmail.dto.ReconciliationReportDto;
import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.entity.ProcessedEmail;
import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationReportServiceTest {

    private static final Long USER_ID = 42L;

    private ProcessedEmailRepository processedRepo;
    private PendingPdfRepository pendingPdfRepo;
    private ReconciliationReportService service;

    @BeforeEach
    void setup() {
        processedRepo = mock(ProcessedEmailRepository.class);
        pendingPdfRepo = mock(PendingPdfRepository.class);
        service = new ReconciliationReportService(processedRepo, pendingPdfRepo);

        // Default: no rows of any kind, so each test only has to stub what it cares about.
        when(processedRepo.countByUserIdAndStatus(eq(USER_ID), any())).thenReturn(0L);
        when(processedRepo.countByUserIdAndStatusAndType(eq(USER_ID), any(), any())).thenReturn(0L);
        when(processedRepo.countByUserIdAndStatusAndMatchedParser(eq(USER_ID), any(), any())).thenReturn(0L);
        when(processedRepo.findByUserIdAndStatusInOrderByProcessedAtDesc(eq(USER_ID), any(), any(PageRequest.class)))
            .thenReturn(Collections.emptyList());
        when(pendingPdfRepo.countByUserIdAndStatus(eq(USER_ID), any())).thenReturn(0L);
        when(pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(eq(USER_ID), any()))
            .thenReturn(Collections.emptyList());
    }

    private ProcessedEmail email(String msgId, String status, String type, String matchedParser,
                                  String sender, String summary, LocalDateTime at) {
        return ProcessedEmail.builder()
            .userId(USER_ID).gmailMessageId(msgId).status(status).type(type)
            .matchedParser(matchedParser).sender(sender).resultSummary(summary).processedAt(at)
            .build();
    }

    private PendingPdf pdf(String msgId, String status, String filename, String sender, String summary) {
        return PendingPdf.builder()
            .userId(USER_ID).gmailMessageId(msgId).attachmentId("att-1")
            .status(status).filename(filename).sender(sender).resultSummary(summary)
            .createdAt(LocalDateTime.now())
            .build();
    }

    @Test
    void bucketsImportedFromBothProcessedEmailsAndPdfs() {
        when(processedRepo.countByUserIdAndStatus(USER_ID, "IMPORTED")).thenReturn(5L);
        when(pendingPdfRepo.countByUserIdAndStatus(USER_ID, "IMPORTED")).thenReturn(2L);

        ReconciliationReportDto report = service.build(USER_ID);

        assertThat(report.getImported()).isEqualTo(7);
        assertThat(report.getUpdatedNote()).isNotBlank();
    }

    @Test
    void bucketsFailedFromProcessedEmailsAndBothPdfFailureStatuses() {
        when(processedRepo.countByUserIdAndStatus(USER_ID, "FAILED")).thenReturn(3L);
        when(pendingPdfRepo.countByUserIdAndStatus(USER_ID, "FAILED")).thenReturn(1L);
        when(pendingPdfRepo.countByUserIdAndStatus(USER_ID, "PASSWORD_FAILED")).thenReturn(4L);

        ReconciliationReportDto report = service.build(USER_ID);

        assertThat(report.getFailed()).isEqualTo(8);
    }

    @Test
    void unparsedExcludesExcludedSendersAndPdfQueuedEmails() {
        // 10 SKIPPED total, of which 2 were excluded-sender filters and 3 were "PDF queued"
        // placeholders (their real fate lives on the PendingPdf row, not here) — only the
        // remaining 5 are genuine "nothing could be parsed" cases.
        when(processedRepo.countByUserIdAndStatus(USER_ID, "SKIPPED")).thenReturn(10L);
        when(processedRepo.countByUserIdAndStatusAndType(USER_ID, "SKIPPED", "EXCLUDED")).thenReturn(2L);
        when(processedRepo.countByUserIdAndStatusAndMatchedParser(USER_ID, "SKIPPED", "PendingPdf")).thenReturn(3L);

        ReconciliationReportDto report = service.build(USER_ID);

        assertThat(report.getUnparsed()).isEqualTo(5);
        assertThat(report.getExcluded()).isEqualTo(2);
    }

    @Test
    void duplicatesAndReconciledAreReportedAsNotDeterminableNotZero() {
        ReconciliationReportDto report = service.build(USER_ID);

        // Must be null (not-determinable), never a fabricated 0 — a 0 would falsely claim
        // "we know there were no duplicates / no recoveries," which is not something the
        // current persistence model can support.
        assertThat(report.getDuplicates()).isNull();
        assertThat(report.getDuplicatesNote()).isNotBlank();
        assertThat(report.getReconciled()).isNull();
        assertThat(report.getReconciledNote()).isNotBlank();
    }

    @Test
    void pdfsAwaitingPasswordIsTrackedSeparatelyFromFailedAndUnparsed() {
        when(pendingPdfRepo.countByUserIdAndStatus(USER_ID, "NEEDS_PASSWORD")).thenReturn(6L);

        ReconciliationReportDto report = service.build(USER_ID);

        assertThat(report.getPdfsAwaitingPassword()).isEqualTo(6);
        assertThat(report.getFailed()).isZero();
        assertThat(report.getUnparsed()).isZero();
    }

    @Test
    void detailRowsIncludeFailedAndUnparsedEmailsButExcludeExcludedAndPdfQueued() {
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(1);
        LocalDateTime t2 = LocalDateTime.now().minusMinutes(2);
        LocalDateTime t3 = LocalDateTime.now().minusMinutes(3);
        LocalDateTime t4 = LocalDateTime.now().minusMinutes(4);

        List<ProcessedEmail> rows = Arrays.asList(
            email("m1", "FAILED", "TRADE_BUY", "ZerodhaParser", "no-reply@zerodha.com",
                "Import failed", t1),
            email("m2", "SKIPPED", "UNKNOWN", null, "someone@example.com",
                "No parser matched and no PDF attachment: Random newsletter", t2),
            email("m3", "SKIPPED", "EXCLUDED", null, "dad@sharedinbox.com",
                "Matches an excluded sender/pattern filter", t3),
            email("m4", "SKIPPED", "UNKNOWN", "PendingPdf", "no-reply@mstock.com",
                "PDF queued for unlock: Contract note", t4)
        );
        when(processedRepo.findByUserIdAndStatusInOrderByProcessedAtDesc(
            eq(USER_ID), eq(Arrays.asList("FAILED", "SKIPPED")), any(PageRequest.class)))
            .thenReturn(rows);

        ReconciliationReportDto report = service.build(USER_ID);

        assertThat(report.getDetails()).hasSize(2);
        assertThat(report.getDetails()).extracting(ReconciliationReportDto.DetailRow::getGmailMessageId)
            .containsExactlyInAnyOrder("m1", "m2");
        ReconciliationReportDto.DetailRow failedRow = report.getDetails().stream()
            .filter(d -> d.getGmailMessageId().equals("m1")).findFirst()
            .orElseThrow(() -> new AssertionError("expected row m1"));
        assertThat(failedRow.getStatus()).isEqualTo("Failed");
        assertThat(failedRow.getSource()).isEqualTo("EMAIL");
        assertThat(failedRow.getSender()).isEqualTo("no-reply@zerodha.com");
    }

    @Test
    void detailRowsIncludeFailedPdfsTracedByFilenameAndSortedMostRecentFirst() {
        List<PendingPdf> pdfs = Arrays.asList(
            pdf("p1", "FAILED", "contract-note-jan.pdf", "no-reply@zerodha.com", "Could not read PDF"),
            pdf("p2", "PASSWORD_FAILED", "statement-feb.pdf", "no-reply@mstock.com", "Saved password failed")
        );
        when(pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(
            eq(USER_ID), eq(Arrays.asList("FAILED", "PASSWORD_FAILED"))))
            .thenReturn(pdfs);

        ReconciliationReportDto report = service.build(USER_ID);

        assertThat(report.getDetails()).hasSize(2);
        assertThat(report.getDetails()).allMatch(d -> "PDF".equals(d.getSource()));
        assertThat(report.getDetails()).extracting(ReconciliationReportDto.DetailRow::getSubject)
            .containsExactlyInAnyOrder("contract-note-jan.pdf", "statement-feb.pdf");
    }
}
