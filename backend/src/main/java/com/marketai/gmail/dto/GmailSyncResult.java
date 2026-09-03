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
