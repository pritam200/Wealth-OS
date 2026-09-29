package com.marketai.gmail.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data @Builder
public class GmailSyncResult {
    private int imported;
    private int skipped;
    private int failed;
    private List<String> summaries;
    private List<SyncLogEntry> logEntries;
    private String error;
    private ReconciliationReport reconciliation;
    private GmailSyncSummaryDto stats;

    @Data @Builder
    public static class ReconciliationReport {
        private int emailsProcessed;
        private int attachmentsProcessed;
        private int transactionsFound;
        private int transactionsImported;
        private int duplicatesSkipped;
        private int failedImports;
        private int pdfsPending;
        private String status; // OK / ACTION_REQUIRED
        /** "✓ Sync Complete" only when every financial event is accounted for; otherwise says what is left. */
        private String headline;
        /** Financial events read (or re-read) this run, from every email body and attachment. */
        private long eventsSeen;
        /** Of those: imported, already on record, or needing nothing for a stated reason. */
        private long eventsAccounted;
        /** Of those: waiting for review or reconciliation, each with its reason. */
        private long eventsUnresolved;
        /** Unresolved events on record in total, including those from earlier runs. */
        private long outstandingUnresolved;
        private List<String> actionItems;
    }

    @Data @Builder
    public static class SyncLogEntry {
        private String gmailMessageId;
        private String sender;
        private String subject;
        private String matchedParser;
        private String status;   // IMPORTED / SKIPPED / FAILED / EXCLUDED / PDF_QUEUED
        private String type;     // TRADE_BUY / EXPENSE / DIVIDEND / MF_SIP / UNKNOWN etc.
        private String detail;   // human-readable one-liner
        private int itemsImported;
        private List<String> pipelineSteps;  // step-by-step trace
    }
}
