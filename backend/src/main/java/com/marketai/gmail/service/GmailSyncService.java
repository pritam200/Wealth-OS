package com.marketai.gmail.service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.gmail.dto.GmailSyncResult;
import com.marketai.gmail.entity.ExcludedSender;
import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.entity.ProcessedEmail;
import com.marketai.gmail.repository.ExcludedSenderRepository;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates a Gmail sync. Every fetched email — with or without a PDF attachment — is handed
 * to {@link EmailLLMParserService}, the single LLM-based replacement for what used to be an
 * 18-parser regex cascade plus two separate AI fallbacks
 * ({@code EmailIntelAgent}/{@code AiEmailExtractor}). This class owns only the things that are
 * genuinely about *syncing* — fetching, dedup/retry bookkeeping, exclusion filters, PDF
 * attachment queueing — and delegates every parsing/extraction/import decision to that service.
 */
@Service
@RequiredArgsConstructor
public class GmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncService.class);
    private static final ConcurrentHashMap<Long, ReentrantLock> USER_SYNC_LOCKS = new ConcurrentHashMap<>();

    private final GmailTokenRepository tokenRepo;
    private final ProcessedEmailRepository processedRepo;
    private final GmailClientService gmailClient;
    private final UserRepository userRepo;
    private final PendingPdfRepository pendingPdfRepo;
    private final ExcludedSenderRepository excludedSenderRepo;
    private final PdfImportService pdfImportService;
    private final com.marketai.ai.review.service.EmailReviewService emailReviewService;
    private final EmailLLMParserService emailLlmParserService;
    private final com.marketai.document.classify.SubjectPatternStage subjectPatternStage;

    // Sender domain, e.g. "no-reply@mstock.com" -> "mstock.com" — the key used to save/reuse
    // a statement password, since a given institution's statements always come from the same
    // domain regardless of which specific account they belong to.
    private static String providerKeyOf(String from) {
        if (from == null) return null;
        int at = from.lastIndexOf('@');
        if (at < 0) return null;
        String domain = from.substring(at + 1).replaceAll("[>\\s]", "").toLowerCase();
        return domain.isEmpty() ? null : domain;
    }

    private static boolean containsIgnoreCase(String text, String... keywords) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (kw != null && lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    public GmailSyncResult syncForUser(Long userId) {
        return syncForUser(userId, "14d");
    }

    public GmailSyncResult syncForUser(Long userId, String lookbackPeriod) {
        ReentrantLock lock = USER_SYNC_LOCKS.computeIfAbsent(userId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.warn("Sync already in progress for user {} — skipping", userId);
            return emptyResult("A sync is already in progress. Please wait for it to complete.");
        }
        try {
            return doSyncForUser(userId, lookbackPeriod, null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Processes an explicit set of message ids instead of re-scanning a date window — the
     * incremental path, where Gmail's history API has already told us exactly what is new.
     *
     * Everything downstream (extraction, dedup, review queue) is identical; only how the message
     * list is obtained differs.
     */
    public GmailSyncResult syncSpecificMessages(Long userId, List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return emptyResult(null);
        }
        ReentrantLock lock = USER_SYNC_LOCKS.computeIfAbsent(userId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.warn("Sync already in progress for user {} — skipping", userId);
            return emptyResult("A sync is already in progress. Please wait for it to complete.");
        }
        try {
            return doSyncForUser(userId, null, messageIds);
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param explicitMessageIds when non-null, exactly these messages are processed and the
     *                           date-window scan is skipped entirely.
     */
    private GmailSyncResult doSyncForUser(Long userId, String lookbackPeriod, List<String> explicitMessageIds) {
        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        if (token == null) return emptyResult("Gmail not connected — please connect your Gmail account first.");

        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return emptyResult("User account not found.");

        int imported = 0, skipped = 0, failed = 0;
        int totalEmails = 0, totalAttachments = 0, totalTransactionsFound = 0, duplicatesSkipped = 0;
        int queuedForReview = 0;
        List<String> summaries = new ArrayList<>();
        List<GmailSyncResult.SyncLogEntry> logEntries = new ArrayList<>();
        List<String> actionItems = new ArrayList<>();
        String syncError = null;

        try {
            Gmail gmail = gmailClient.buildGmailService(token.getAccessToken(), token.getRefreshToken(),
                (newAccessToken, newRefreshToken, expiresIn) -> {
                    log.info("Google auto-refreshed access token for user {} — persisting to DB", userId);
                    token.setAccessToken(newAccessToken);
                    if (newRefreshToken != null) token.setRefreshToken(newRefreshToken);
                    token.setExpiresAt(LocalDateTime.now().plusSeconds(expiresIn != null ? expiresIn : 3600));
                    tokenRepo.save(token);
                });

            List<Message> messages;
            try {
                if (explicitMessageIds != null) {
                    // Incremental path: Gmail's history API already told us precisely what is
                    // new, so we fetch only those. A window scan here would re-pay 20 quota
                    // units per message for mail we have already processed.
                    messages = new ArrayList<>();
                    for (String id : explicitMessageIds) {
                        try {
                            messages.add(gmailClient.getMessage(gmail, id));
                        } catch (Exception e) {
                            // A single unreadable message (deleted between notification and
                            // fetch) must not abort the whole batch.
                            log.warn("Could not fetch message {}: {}", id, e.getMessage());
                        }
                    }
                } else {
                    messages = gmailClient.fetchRecentMessages(gmail, 300, lookbackPeriod);
                }
            } catch (com.google.api.client.http.HttpResponseException httpEx) {
                if (httpEx.getStatusCode() == 401) {
                    return emptyResult("Gmail token expired. Please disconnect and reconnect your Gmail account.");
                }
                throw httpEx;
            }

            List<String> excludedPatterns = new ArrayList<>();
            for (ExcludedSender es : excludedSenderRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
                excludedPatterns.add(es.getPattern());
            }

            for (Message message : messages) {
                totalEmails++;
                String msgId = message.getId();
                ProcessedEmail existing = processedRepo.findByUserIdAndGmailMessageId(userId, msgId).orElse(null);
                if (existing != null) {
                    // Only two outcomes are genuinely terminal: a completed import, and a sender
                    // the user deliberately excluded. Everything else — FAILED (transient error),
                    // SKIPPED/UNKNOWN (nothing extracted at the time — an improved model or a
                    // recovered LLM should get another chance), and REVIEW/REVIEW_REQUIRED
                    // (sender-trust hold-back or low-confidence classification — re-running is
                    // safe because EmailReviewService.enqueue() is a no-op once the user has
                    // resolved the item) must be retried on every sync, or the underlying
                    // transaction is silently and permanently dropped. A PDF already queued for
                    // password resolution keeps its own retry lifecycle in PendingPdf, so it is
                    // not re-queued here.
                    // A SKIPPED row created when this email was queued for human review stays
                    // SKIPPED forever even after the review item is resolved — decide() only
                    // updates EmailReviewItem, never this row (see EmailReviewService.decide()).
                    // Without checking the review queue directly, an already-judged email would
                    // be re-fetched and re-parsed on every sync indefinitely.
                    boolean permanentlyResolved = "IMPORTED".equals(existing.getStatus())
                        || "EXCLUDED".equals(existing.getType())
                        || "PendingPdf".equals(existing.getMatchedParser())
                        || ("SKIPPED".equals(existing.getStatus()) && emailReviewService.isFullyResolved(userId, msgId));
                    if (permanentlyResolved) {
                        skipped++;
                        duplicatesSkipped++;
                        continue;
                    }
                    // saveProcessed() below updates this same row rather than inserting a new one
                    // (the (user_id, gmail_message_id) unique constraint would otherwise reject it).
                    log.info("Retrying previously unresolved email {} for user {} (status={})",
                        msgId, userId, existing.getStatus());
                }
                // A forwarded/resent email gets a NEW Gmail message id, so this check alone
                // won't catch it — but ParsedEmailImporter's own content-keyed checks
                // (isDuplicateTrade/isDuplicateIncome/isDuplicateExpense, FD/RD bank+amount+date)
                // still refuse to double-book the actual financial record regardless of message
                // id, which is what actually matters for correctness.

                String from    = gmailClient.getFrom(message);
                String subject = gmailClient.getSubject(message);
                String body    = gmailClient.getBodyText(message);
                List<String> steps = new ArrayList<>();
                steps.add("Fetched email from Gmail");

                if (!excludedPatterns.isEmpty()
                        && containsIgnoreCase(from + " " + subject + " " + body, excludedPatterns.toArray(new String[0]))) {
                    steps.add("Matched excluded sender/pattern filter — skipped");
                    saveProcessed(userId, msgId, "EXCLUDED", "SKIPPED", "Matches an excluded sender/pattern filter", from, null);
                    logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                        .gmailMessageId(msgId).sender(from).subject(subject)
                        .status("EXCLUDED").detail("Matches excluded sender filter")
                        .pipelineSteps(steps).build());
                    skipped++;
                    continue;
                }

                // Check for PDF attachments — even when body extraction may succeed on its own,
                // also try PDFs if the email has them (contract notes/statements often carry
                // detail only present in the PDF, not the email body summary).
                List<GmailClientService.PdfAttachmentRef> pdfRefs = gmailClient.findPdfAttachments(message);
                boolean queuedPdf = false;
                if (!pdfRefs.isEmpty()) {
                    totalAttachments += pdfRefs.size();
                    steps.add("Found " + pdfRefs.size() + " PDF attachment(s)");
                    for (GmailClientService.PdfAttachmentRef pdf : pdfRefs) {
                        // Look up by filename, not attachmentId — Gmail can return a different
                        // attachmentId for the same physical attachment on repeated fetches,
                        // which previously defeated this dedup check entirely and spawned a
                        // fresh PendingPdf (and a fresh import attempt) on every resync.
                        PendingPdf existingPdf = pendingPdfRepo.findByUserIdAndGmailMessageIdAndFilename(userId, msgId, pdf.filename).orElse(null);
                        if (existingPdf != null) {
                            if ("NEEDS_PASSWORD".equals(existingPdf.getStatus()) || "PASSWORD_FAILED".equals(existingPdf.getStatus()) || "FAILED".equals(existingPdf.getStatus())) {
                                steps.add("PDF " + pdf.filename + " — retrying (" + existingPdf.getStatus() + ")");
                                try {
                                    pdfImportService.tryAutoUnlock(userId, existingPdf);
                                    PendingPdf refreshed = pendingPdfRepo.findById(existingPdf.getId()).orElse(existingPdf);
                                    if ("IMPORTED".equals(refreshed.getStatus())) {
                                        steps.add("Auto-unlocked on retry: " + refreshed.getResultSummary());
                                    } else {
                                        steps.add("Still locked — no matching saved password found");
                                    }
                                } catch (Exception e) {
                                    steps.add("Auto-unlock retry failed: " + e.getMessage());
                                }
                            } else {
                                steps.add("PDF " + pdf.filename + " already processed — skipping");
                            }
                            continue;
                        }
                        String providerKey = providerKeyOf(from);

                        // Classify once (subject/body only — the attachment is still encrypted)
                        // to learn which PasswordStrategy this issuer's statements use. The model
                        // never sees or produces PAN/DOB/a password — only the strategy name,
                        // which PdfImportService later resolves deterministically against the
                        // user's stored identity. A hint that doesn't map to a known strategy
                        // (see EmailLLMParserService#mapPasswordHint) is stored as null — fail
                        // safe, no password guess.
                        EmailLLMParserService.Classification cls = emailLlmParserService.classify(from, subject, body);
                        String hint = cls.passwordHintType() != null ? cls.passwordHintType().name()
                            : pdfImportService.findKnownHint(userId, providerKey);

                        // Best-effort subject-line classification, so password learning can
                        // tell a card statement apart from a bank statement from the same
                        // sender domain instead of assuming one password fits every statement
                        // kind that institution ever sends.
                        String documentType = subjectPatternStage
                            .classify(com.marketai.document.classify.ClassificationCandidate.email(from, subject, null))
                            .map(com.marketai.document.classify.DocumentClassification::docType)
                            .orElse(null);
                        PendingPdf savedPdf = pendingPdfRepo.save(PendingPdf.builder()
                            .userId(userId).gmailMessageId(msgId).attachmentId(pdf.attachmentId)
                            .filename(pdf.filename).sender(from).subject(subject)
                            .providerKey(providerKey)
                            .documentType(documentType)
                            .passwordHint(hint)
                            .build());
                        log.info("NEW PendingPdf created: id={}, file={}, provider={}, hint={}", savedPdf.getId(), pdf.filename, providerKey, hint);
                        steps.add("Queued PDF: " + pdf.filename + " (provider: " + providerKey + ", hint: " + (hint != null ? hint : "unknown") + ")");
                        try {
                            pdfImportService.tryAutoUnlock(userId, savedPdf);
                            // Reload to check if auto-unlock succeeded
                            PendingPdf refreshed = pendingPdfRepo.findById(savedPdf.getId()).orElse(savedPdf);
                            if ("IMPORTED".equals(refreshed.getStatus())) {
                                steps.add("Auto-unlocked and imported: " + refreshed.getResultSummary());
                            } else if ("PASSWORD_FAILED".equals(refreshed.getStatus())) {
                                steps.add("Auto-unlock failed: saved password did not work — " + refreshed.getResultSummary());
                            } else {
                                steps.add("No saved password found — queued for manual unlock");
                            }
                        } catch (Exception e) {
                            steps.add("Auto-unlock error: " + e.getMessage());
                            log.warn("Auto-unlock attempt errored for pending {}: {}", savedPdf.getId(), e.getMessage());
                        }
                        queuedPdf = true;
                    }
                }

                // Single unconditional LLM extraction call for the email body — replaces the
                // former 18-parser cascade plus the EmailIntelAgent/AiEmailExtractor fallbacks.
                EmailLLMParserService.Classification bodyClassification = emailLlmParserService.classify(from, subject, body);
                EmailLLMParserService.Result result = emailLlmParserService.process(
                    userId, user, from, subject, body, msgId, bodyClassification);

                totalTransactionsFound += result.getImported() + result.getQueuedForReview() + result.getRejected();

                switch (result.getOutcome()) {
                    case IMPORTED -> {
                        imported += result.getImported();
                        queuedForReview += result.getQueuedForReview();
                        summaries.addAll(result.getSummaries());
                        steps.add("LLM extraction: " + result.getImported() + " transaction(s) imported"
                            + (result.getQueuedForReview() > 0 ? ", " + result.getQueuedForReview() + " queued for review" : ""));
                        saveProcessed(userId, msgId, "IMPORTED", "IMPORTED",
                            String.join("; ", result.getSummaries()), from, "EmailLLMParserService");
                        logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                            .gmailMessageId(msgId).sender(from).subject(subject)
                            .matchedParser("EmailLLMParserService").status("IMPORTED")
                            .itemsImported(result.getImported())
                            .detail(String.join("; ", result.getSummaries()))
                            .pipelineSteps(steps).build());
                    }
                    case REVIEW, UNAVAILABLE -> {
                        queuedForReview += result.getQueuedForReview();
                        steps.add("Sent to review queue: " + result.getDetail());
                        saveProcessed(userId, msgId, "UNKNOWN", "SKIPPED",
                            result.getDetail() != null ? result.getDetail() : "Queued for review", from, "EmailLLMParserService");
                        logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                            .gmailMessageId(msgId).sender(from).subject(subject)
                            .matchedParser("EmailLLMParserService").status("REVIEW_REQUIRED")
                            .detail(result.getDetail()).pipelineSteps(steps).build());
                        skipped++;
                    }
                    case NOT_FINANCIAL -> {
                        if (!queuedPdf) {
                            String summary = "No transaction found in body and no PDF attachment: " + subject;
                            steps.add(summary);
                            saveProcessed(userId, msgId, "UNKNOWN", "SKIPPED", summary, from, null);
                            logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                                .gmailMessageId(msgId).sender(from).subject(subject)
                                .status("SKIPPED").detail(summary).pipelineSteps(steps).build());
                        } else {
                            String summary = "PDF queued for unlock: " + subject;
                            saveProcessed(userId, msgId, "UNKNOWN", "SKIPPED", summary, from, "PendingPdf");
                            logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                                .gmailMessageId(msgId).sender(from).subject(subject)
                                .matchedParser("PDF").status("PDF_QUEUED").detail(summary)
                                .pipelineSteps(steps).build());
                        }
                        skipped++;
                    }
                }
            }

            // After processing all emails, try to auto-unlock any remaining locked PDFs
            // (covers PDFs from previous syncs that now have a saved password available)
            try {
                int bulkUnlocked = pdfImportService.bulkAutoUnlock(userId);
                if (bulkUnlocked > 0) {
                    summaries.add("Auto-unlocked " + bulkUnlocked + " previously locked statement(s) using saved passwords");
                    imported += bulkUnlocked;
                }
            } catch (Exception e) {
                log.warn("Bulk auto-unlock failed: {}", e.getMessage());
            }

            token.setLastSyncAt(LocalDateTime.now());
            token.setImportedCount((token.getImportedCount() != null ? token.getImportedCount() : 0) + imported);
            tokenRepo.save(token);

        } catch (Exception e) {
            log.error("Gmail sync failed for user {}: {}", userId, e.getMessage(), e);
            syncError = e.getMessage();
        }

        int pendingPdfs = 0;
        try {
            pendingPdfs = pdfImportService.list(userId).size();
        } catch (Exception ignored) {}
        if (pendingPdfs > 0) {
            actionItems.add(pendingPdfs + " PDF attachment(s) still awaiting password unlock");
        }

        GmailSyncResult.ReconciliationReport reconciliation = GmailSyncResult.ReconciliationReport.builder()
            .emailsProcessed(totalEmails)
            .attachmentsProcessed(totalAttachments)
            .transactionsFound(totalTransactionsFound)
            .transactionsImported(imported)
            .duplicatesSkipped(duplicatesSkipped)
            .failedImports(failed)
            .pdfsPending(pendingPdfs)
            .status(failed == 0 && pendingPdfs == 0 && syncError == null ? "OK" : "ACTION_REQUIRED")
            .actionItems(actionItems)
            .build();

        LocalDateTime lastSync = tokenRepo.findByUserId(userId).map(GmailToken::getLastSyncAt).orElse(null);

        return GmailSyncResult.builder()
                .imported(imported).skipped(skipped).failed(failed)
                .summaries(summaries).logEntries(logEntries)
                .error(syncError)
                .reconciliation(reconciliation)
                .stats(com.marketai.gmail.dto.GmailSyncSummaryDto.builder()
                    .lastSync(lastSync)
                    .scanned(totalEmails)
                    .newlyImported(imported)
                    .duplicatesSkipped(duplicatesSkipped)
                    .failed(failed)
                    .extractedTransactions(totalTransactionsFound)
                    .queuedForReview(queuedForReview)
                    .build())
                .build();
    }

    private GmailSyncResult emptyResult(String error) {
        return GmailSyncResult.builder()
            .imported(0).skipped(0).failed(0)
            .summaries(new ArrayList<>()).logEntries(new ArrayList<>())
            .error(error).build();
    }

    public void syncAllUsers() {
        tokenRepo.findAll().forEach(token -> {
            try { syncForUser(token.getUser().getId()); } catch (Exception e) {
                log.error("Scheduled sync failed for user {}: {}", token.getUser().getId(), e.getMessage());
            }
        });
    }

    // Upsert, not insert: a retried FAILED email already has a row, and (user_id,
    // gmail_message_id) is unique — a blind insert would throw and lose the new outcome.
    private void saveProcessed(Long userId, String msgId, String type, String status, String summary, String sender, String matchedParser) {
        try {
            ProcessedEmail row = processedRepo.findByUserIdAndGmailMessageId(userId, msgId)
                .orElseGet(() -> ProcessedEmail.builder().userId(userId).gmailMessageId(msgId).build());
            row.setType(type);
            row.setStatus(status);
            row.setMatchedParser(matchedParser);
            row.setSender(sender != null && sender.length() > 320 ? sender.substring(0, 320) : sender);
            row.setResultSummary(summary != null && summary.length() > 900 ? summary.substring(0, 900) : summary);
            row.setProcessedAt(LocalDateTime.now());
            processedRepo.save(row);
        } catch (Exception e) {
            log.warn("Failed to save ProcessedEmail for message {}: {}", msgId, e.getMessage());
        }
    }
}
