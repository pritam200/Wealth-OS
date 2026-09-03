package com.marketai.gmail.service;

import com.marketai.gmail.dto.ReconciliationReportDto;
import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.entity.ProcessedEmail;
import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the persistent, queryable reconciliation report by reading the existing
 * {@code ProcessedEmail} (per-email trace) and {@code PendingPdf} (per-attachment trace)
 * tables — it never writes anything, and it does not touch the one-shot
 * {@link com.marketai.gmail.dto.GmailSyncResult.ReconciliationReport} returned by the sync
 * endpoints.
 *
 * Bucketing rules (see also the field-level docs on ReconciliationReportDto):
 * <ul>
 *   <li>Imported  = ProcessedEmail(status=IMPORTED) + PendingPdf(status=IMPORTED)</li>
 *   <li>Failed    = ProcessedEmail(status=FAILED) + PendingPdf(status=FAILED or PASSWORD_FAILED)</li>
 *   <li>Unparsed  = ProcessedEmail(status=SKIPPED) minus the EXCLUDED and PDF-queued sub-cases</li>
 *   <li>Excluded  = ProcessedEmail(status=SKIPPED, type=EXCLUDED) — reported separately, not folded
 *       into Unparsed, since it was an intentional filter, not a parsing gap</li>
 *   <li>PDFs awaiting password = PendingPdf(status=NEEDS_PASSWORD) — not yet attempted, so it is
 *       neither Failed nor Unparsed</li>
 *   <li>Updated, Duplicate, Reconciled — see the *Note fields; none of the three can be computed
 *       honestly from what is persisted today (see class javadoc on each note for why), so they
 *       are reported as "not determinable" rather than a fabricated number.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReconciliationReportService {

    static final int DETAIL_CAP = 200;

    static final String UPDATED_NOTE =
        "Not separately counted — folded into 'imported'. PortfolioService.addHolding() looks up " +
        "an existing holding by symbol and either recalculates its average cost or creates a new " +
        "one, but returns the same Holding either way; there is currently no signal threaded back " +
        "to GmailSyncService/ParsedEmailImporter indicating which branch ran, so ProcessedEmail's " +
        "IMPORTED status cannot distinguish 'new holding created' from 'existing holding topped up'.";

    static final String DUPLICATES_NOTE =
        "Not determinable from current persistence. Message-level duplicate skips " +
        "(GmailSyncService: existsByUserIdAndGmailMessageId) happen BEFORE any ProcessedEmail row " +
        "is written for that pass, so a repeat sync of an already-processed email leaves no new " +
        "row to count. Content-level duplicate skips inside ParsedEmailImporter " +
        "(isDuplicateTrade/isDuplicateIncome/isDuplicateExpense) return silently without signaling " +
        "the caller, so GmailSyncService still records status=IMPORTED for those emails — " +
        "indistinguishable from a real import in ProcessedEmail today.";

    static final String RECONCILED_NOTE =
        "Not determinable from current persistence. Both /api/gmail/resync (full resync) and " +
        "/api/gmail/retry-failed delete the existing FAILED/SKIPPED ProcessedEmail rows before " +
        "resyncing (see GmailController.fullResync/retryFailed), so once an email is reprocessed " +
        "successfully there is no remaining record of its prior failed/unparsed state to compare " +
        "against and confirm a recovery.";

    private final ProcessedEmailRepository processedRepo;
    private final PendingPdfRepository pendingPdfRepo;

    public ReconciliationReportDto build(Long userId) {
        long importedEmails = processedRepo.countByUserIdAndStatus(userId, "IMPORTED");
        long failedEmails = processedRepo.countByUserIdAndStatus(userId, "FAILED");
        long skippedTotal = processedRepo.countByUserIdAndStatus(userId, "SKIPPED");
        long excludedEmails = processedRepo.countByUserIdAndStatusAndType(userId, "SKIPPED", "EXCLUDED");
        long pdfQueuedEmails = processedRepo.countByUserIdAndStatusAndMatchedParser(userId, "SKIPPED", "PendingPdf");
        long unparsedEmails = Math.max(0, skippedTotal - excludedEmails - pdfQueuedEmails);

        long importedPdfs = pendingPdfRepo.countByUserIdAndStatus(userId, "IMPORTED");
        long failedPdfs = pendingPdfRepo.countByUserIdAndStatus(userId, "FAILED");
        long passwordFailedPdfs = pendingPdfRepo.countByUserIdAndStatus(userId, "PASSWORD_FAILED");
        long needsPasswordPdfs = pendingPdfRepo.countByUserIdAndStatus(userId, "NEEDS_PASSWORD");

        List<ReconciliationReportDto.DetailRow> details = buildDetails(userId);

        return ReconciliationReportDto.builder()
            .imported((int) (importedEmails + importedPdfs))
            .updatedNote(UPDATED_NOTE)
            .duplicates(null)
            .duplicatesNote(DUPLICATES_NOTE)
            .failed((int) (failedEmails + failedPdfs + passwordFailedPdfs))
            .unparsed((int) unparsedEmails)
            .reconciled(null)
            .reconciledNote(RECONCILED_NOTE)
            .excluded((int) excludedEmails)
            .pdfsAwaitingPassword((int) needsPasswordPdfs)
            .details(details)
            .generatedAt(LocalDateTime.now())
            .build();
    }

    private List<ReconciliationReportDto.DetailRow> buildDetails(Long userId) {
        List<ReconciliationReportDto.DetailRow> details = new ArrayList<>();

        List<ProcessedEmail> problemEmails = processedRepo.findByUserIdAndStatusInOrderByProcessedAtDesc(
            userId, Arrays.asList("FAILED", "SKIPPED"), PageRequest.of(0, DETAIL_CAP));
        for (ProcessedEmail pe : problemEmails) {
            if ("SKIPPED".equals(pe.getStatus())) {
                if ("EXCLUDED".equals(pe.getType())) continue; // intentional, not a gap
                if ("PendingPdf".equals(pe.getMatchedParser())) continue; // tracked via PendingPdf below
            }
            details.add(ReconciliationReportDto.DetailRow.builder()
                .gmailMessageId(pe.getGmailMessageId())
                .subject(null) // ProcessedEmail does not persist the email subject
                .sender(pe.getSender())
                .matchedParser(pe.getMatchedParser())
                .status("FAILED".equals(pe.getStatus()) ? "Failed" : "Unparsed")
                .reason(pe.getResultSummary())
                .processedAt(pe.getProcessedAt())
                .source("EMAIL")
                .build());
        }

        List<PendingPdf> problemPdfs = pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(
            userId, Arrays.asList("FAILED", "PASSWORD_FAILED"));
        for (PendingPdf pdf : problemPdfs) {
            details.add(ReconciliationReportDto.DetailRow.builder()
                .gmailMessageId(pdf.getGmailMessageId())
                .subject(pdf.getFilename())
                .sender(pdf.getSender())
                .matchedParser(null)
                .status("Failed")
                .reason(pdf.getResultSummary())
                .processedAt(pdf.getUnlockedAt() != null ? pdf.getUnlockedAt() : pdf.getCreatedAt())
                .source("PDF")
                .build());
        }

        details.sort(Comparator.comparing(
            ReconciliationReportDto.DetailRow::getProcessedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));

        if (details.size() > DETAIL_CAP) {
            details = details.subList(0, DETAIL_CAP);
        }
        return details;
    }
}
