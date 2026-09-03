package com.marketai.gmail.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The persistent, queryable counterpart to {@link GmailSyncResult.ReconciliationReport}
 * (which is a one-shot snapshot returned only from the sync endpoints and never stored).
 * This DTO is built by querying the existing {@code ProcessedEmail} and {@code PendingPdf}
 * tables — it does not introduce any new storage — so it reflects the full history of every
 * email/attachment ever processed for the user, not just the last sync run.
 *
 * Two of the six requested buckets ("Updated" and "Reconciled") cannot be honestly computed
 * from what is currently persisted; see {@link #updatedNote} and {@link #reconciledNote} for
 * exactly why, rather than guessing a number. Same for "Duplicate" — see {@link #duplicatesNote}.
 */
@Data
@Builder
public class ReconciliationReportDto {

    // ---- Imported ----
    // A new domain record (Transaction/Holding/Income/Expense/FD/RD/etc.) was created.
    // Includes both ProcessedEmail rows with status=IMPORTED and PendingPdf rows with
    // status=IMPORTED (a successfully unlocked-and-parsed PDF attachment).
    private int imported;

    // "Updated" (an existing holding topped up rather than created new) is NOT separately
    // counted — see updatedNote. It is folded into `imported` above.
    private String updatedNote;

    // ---- Duplicate ----
    // Null means "not determinable from current persistence" (see duplicatesNote) rather
    // than a fabricated 0, which would falsely claim "no duplicates were ever skipped."
    private Integer duplicates;
    private String duplicatesNote;

    // ---- Failed ----
    // A parser (or the PDF-unlock pipeline) matched something but the import step threw,
    // was rejected by FinancialDataValidator, or the saved PDF password stopped working.
    private int failed;

    // ---- Unparsed ----
    // No parser and no AI fallback extracted anything at all. Excludes emails that were
    // deliberately skipped via an excluded-sender rule (see `excluded` below) and emails
    // whose fate is instead tracked as a PendingPdf row (see `pdfsAwaitingPassword`), so
    // this bucket only counts genuine "we found nothing here" cases.
    private int unparsed;

    // ---- Reconciled ----
    // Null means "not determinable from current persistence" (see reconciledNote).
    private Integer reconciled;
    private String reconciledNote;

    // ---- Extra context (not one of the six requested buckets, but needed so nothing
    // silently disappears from the totals) ----

    // Emails skipped because they matched a user-defined excluded-sender pattern —
    // intentional, not a parsing gap.
    private int excluded;

    // PDF attachments still waiting on a password (NEEDS_PASSWORD) — not yet attempted,
    // so neither "failed" nor "unparsed" applies to them yet.
    private int pdfsAwaitingPassword;

    // Failed/Unparsed detail rows only, most recent first, capped at 200 — each traceable
    // back to its source email or attachment.
    private List<DetailRow> details;

    private LocalDateTime generatedAt;

    @Data
    @Builder
    public static class DetailRow {
        private String gmailMessageId;
        // Email subject for email-sourced rows, or the attachment filename for PDF-sourced
        // rows. ProcessedEmail does not persist the email subject today, so this is null
        // for email-sourced rows rather than a guess — see resultSummary/reason for context.
        private String subject;
        private String sender;
        private String matchedParser;
        private String status; // "Failed" or "Unparsed"
        private String reason;
        private LocalDateTime processedAt;
        private String source; // "EMAIL" or "PDF"
    }
}
