package com.marketai.gmail.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * The one shape callers need to answer "what did the last sync actually do?" — previously these
 * numbers were split across GmailToken.lastSyncAt and GmailSyncResult.reconciliation, forcing
 * every caller to stitch two sources together and invent its own field names.
 */
@Data @Builder
public class GmailSyncSummaryDto {
    private LocalDateTime lastSync;
    private int scanned;               // emails Gmail returned and we looked at
    private int newlyImported;         // emails whose transactions were imported this run
    private int duplicatesSkipped;     // already-processed emails skipped
    private int failed;                // imports that errored (retried on next sync)
    private int extractedTransactions; // parsed transaction records found across all emails
    private int queuedForReview;       // uncertain extractions awaiting human review
}
