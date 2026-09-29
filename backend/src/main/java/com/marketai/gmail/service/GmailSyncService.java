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
    /** Returned when the user's mailbox is already being synced; the job runner requeues on it
     *  rather than counting it as a failed attempt. */
    public static final String SYNC_IN_PROGRESS = "A sync is already in progress. Please wait for it to complete.";

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
    private final com.marketai.sync.service.SyncLockService syncLockService;
    private final com.marketai.gmail.ledger.FinancialEventLedger ledger;
    private final com.marketai.gmail.ledger.EmailManifestService manifestService;

    /** Beyond this a text attachment is not sent to the extractor (25 extraction calls); it is flagged instead. */
    static final int MAX_TEXT_ATTACHMENT_CHARS = 200_000;

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
        return syncForUser(userId, lookbackPeriod, null);
    }

    /**
     * @param beforeSync bookkeeping reset to run once the lock is held (see
     *                   {@link GmailResyncResetService}) — never before, or a re-sync refused
     *                   because another sync is running would still have wiped state
     */
    public GmailSyncResult syncForUser(Long userId, String lookbackPeriod, Runnable beforeSync) {
        var lock = syncLockService.tryAcquire(userId);
        if (lock.isEmpty()) {
            log.warn("Sync already in progress for user {} — skipping", userId);
            return emptyResult(SYNC_IN_PROGRESS);
        }
        try (var held = lock.get()) {
            if (beforeSync != null) beforeSync.run();
            return doSyncForUser(userId, lookbackPeriod, null);
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
        var lock = syncLockService.tryAcquire(userId);
        if (lock.isEmpty()) {
            log.warn("Sync already in progress for user {} — skipping", userId);
            return emptyResult(SYNC_IN_PROGRESS);
        }
        try (var held = lock.get()) {
            return doSyncForUser(userId, null, messageIds);
        }
    }

    /**
     * @param explicitMessageIds when non-null, exactly these messages are processed and the
     *                           date-window scan is skipped entirely.
     */
    /** ProcessedEmail.type for an email that was read in full and holds nothing to book. */
    static final String NO_TRANSACTION = "NO_TRANSACTION";

    private GmailSyncResult doSyncForUser(Long userId, String lookbackPeriod, List<String> explicitMessageIds) {
        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        if (token == null) return emptyResult("Gmail not connected — please connect your Gmail account first.");

        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return emptyResult("User account not found.");

        int imported = 0, skipped = 0, failed = 0;
        int totalEmails = 0, totalAttachments = 0, totalTransactionsFound = 0, duplicatesSkipped = 0;
        int queuedForReview = 0, conflicts = 0;
        BOOKKEEPING_FAILURES.get()[0] = 0;
        com.marketai.gmail.ledger.FinancialEventLedger.resetFailures();
        LocalDateTime runStart = LocalDateTime.now();
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

            // Ids first, bodies later: a message already fully handled is recognised from its id
            // alone and never downloaded again, so a large backfill costs one fetch per new email.
            List<String> messageIds;
            try {
                if (explicitMessageIds != null) {
                    // Incremental path: Gmail's history API already told us precisely what is new.
                    messageIds = explicitMessageIds;
                } else {
                    GmailClientService.MessageIdList listed = gmailClient.listMessageIds(gmail, lookbackPeriod);
                    messageIds = listed.ids();
                    if (listed.truncated()) {
                        actionItems.add("More than " + GmailClientService.MESSAGE_ID_SAFETY_CAP
                            + " emails in the last " + lookbackPeriod + " — the oldest were not scanned. "
                            + "Sync a shorter window first, then extend it.");
                    }
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

            for (String msgId : messageIds) {
                totalEmails++;
                // Every message below is independent. A bug or unexpected exception on ONE
                // email (a malformed attachment, an LLM response the router couldn't parse,
                // etc.) must not abort the rest of this sync run and leave every message after
                // it in the batch unprocessed until the next scheduled/manual sync — this catch
                // is the backstop for exactly that. Known, expected failure points already have
                // their own inner try/catch (PDF auto-unlock, bulk unlock) and are unaffected.
                try {
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
                    // NO_TRANSACTION is a completed read that found nothing to book — repeating the
                    // same LLM calls on every sync would only cost time on a full-mailbox backfill.
                    boolean permanentlyResolved = "IMPORTED".equals(existing.getStatus())
                        || "EXCLUDED".equals(existing.getType())
                        || NO_TRANSACTION.equals(existing.getType())
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

                // A fetch failure (message deleted meanwhile, transient API error) lands in the
                // per-message catch below and is recorded as FAILED, so it is retried next sync
                // rather than silently skipped as before.
                Message message = gmailClient.getMessage(gmail, msgId);
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
                // Classified once per email: it serves both the password hint and the body's sender check.
                EmailLLMParserService.Classification bodyClassification = null;
                if (!pdfRefs.isEmpty()) {
                    totalAttachments += pdfRefs.size();
                    steps.add("Found " + pdfRefs.size() + " document attachment(s)");
                    for (GmailClientService.PdfAttachmentRef pdf : pdfRefs) {
                        // Look up by filename, not attachmentId — Gmail can return a different
                        // attachmentId for the same physical attachment on repeated fetches,
                        // which previously defeated this dedup check entirely and spawned a
                        // fresh PendingPdf (and a fresh import attempt) on every resync.
                        PendingPdf existingPdf = pendingPdfRepo.findByUserIdAndGmailMessageIdAndFilename(userId, msgId, pdf.filename).orElse(null);
                        if (existingPdf != null) {
                            // A scan parked as unreadable is retried only once an image-capable model
                            // is configured — otherwise it would be downloaded again every sync.
                            boolean retryScan = "NEEDS_OCR".equals(existingPdf.getStatus()) && pdfImportService.canReadScans();
                            if (retryScan || "NEEDS_PASSWORD".equals(existingPdf.getStatus()) || "PASSWORD_FAILED".equals(existingPdf.getStatus()) || "FAILED".equals(existingPdf.getStatus())) {
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
                                    actionItems.add("Statement " + pdf.filename + " could not be opened on retry: " + e.getMessage());
                                }
                            } else {
                                steps.add("PDF " + pdf.filename + " already processed — skipping");
                            }
                            queuedPdf = true; // keeps the email tied to its PDF's own lifecycle
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
                        if (bodyClassification == null) {
                            bodyClassification = emailLlmParserService.classify(userId, msgId, from, subject, body);
                        }
                        EmailLLMParserService.Classification cls = bodyClassification;
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
                            actionItems.add("Statement " + pdf.filename + " could not be opened: " + e.getMessage());
                        }
                        queuedPdf = true;
                    }
                }

                // Single unconditional LLM extraction call for the email body — replaces the
                // former 18-parser cascade plus the EmailIntelAgent/AiEmailExtractor fallbacks.
                if (bodyClassification == null) {
                    bodyClassification = emailLlmParserService.classify(userId, msgId, from, subject, body);
                }
                EmailLLMParserService.Result result = emailLlmParserService.process(
                    userId, user, from, subject, body, msgId, bodyClassification);

                // Every attachment is accounted for: PDFs and images went to the statement queue
                // above; text attachments are read here like the body; anything else is named in
                // the manifest, and flagged for a person when the email is a financial one.
                List<GmailClientService.AttachmentInfo> attachments = gmailClient.listAttachments(message);
                List<String> attachmentNotes = new ArrayList<>();
                int attachmentsRead = pdfRefs.size();
                boolean financialEmail = result.getOutcome() != EmailLLMParserService.Outcome.NOT_FINANCIAL
                    || queuedPdf || emailLlmParserService.looksFinancial(from, subject, body);
                int images = 0;
                for (GmailClientService.AttachmentInfo att : attachments) {
                    String attKey = "att:" + att.filename();
                    switch (att.kind()) {
                        case TEXT -> {
                            try {
                                byte[] bytes = gmailClient.downloadAttachment(gmail, msgId, att.attachmentId());
                                String text = GmailClientService.attachmentText(bytes, att.filename(), att.mimeType());
                                if (text.isBlank()) {
                                    attachmentNotes.add(att.filename() + ": empty");
                                    continue;
                                }
                                if (text.length() > MAX_TEXT_ATTACHMENT_CHARS) {
                                    String why = att.filename() + " is too large to read automatically (" + text.length()
                                        + " characters). Open it and add any transactions it lists by hand, then mark this resolved.";
                                    attachmentNotes.add(att.filename() + ": too large to read automatically");
                                    if (financialEmail) recordAttachmentProblem(userId, msgId, att, attKey, why);
                                    continue;
                                }
                                EmailLLMParserService.Result read = emailLlmParserService.process(
                                    userId, user, from, subject, text, msgId, bodyClassification,
                                    EmailLLMParserService.attachmentItemIndexBase(att.filename()),
                                    new EmailLLMParserService.SourceDoc(att.attachmentId(), att.filename(),
                                        EmailLLMParserService.sha256(text), com.marketai.common.ledger.Provenance.ATTACHMENT_LLM));
                                result = EmailLLMParserService.merge(result, read);
                                attachmentsRead++;
                                totalAttachments++;
                                ledger.resolveIfOpen(userId, msgId, attKey, "Read on a later sync.");
                                steps.add("Read attachment " + att.filename() + ": " + read.getExtracted() + " event(s)");
                            } catch (Exception e) {
                                // Retried with the email: marking the whole result incomplete keeps it open.
                                String why = att.filename() + " could not be downloaded (" + e.getMessage() + ") — it will be retried on the next sync.";
                                attachmentNotes.add(att.filename() + ": could not be downloaded");
                                recordAttachmentProblem(userId, msgId, att, attKey, why);
                                result = EmailLLMParserService.merge(result, EmailLLMParserService.Result.builder()
                                    .outcome(EmailLLMParserService.Outcome.REVIEW).incomplete(true).detail(why).build());
                            }
                        }
                        case UNSUPPORTED -> {
                            attachmentNotes.add(att.label() + ": not read automatically");
                            if (financialEmail) {
                                recordAttachmentProblem(userId, msgId, att, attKey, att.label() + " could not be read automatically. "
                                    + "Open it and add any transactions it lists by hand, then mark this resolved.");
                            }
                        }
                        case NOT_A_DOCUMENT -> {
                            if (att.mimeType() != null && att.mimeType().startsWith("image/")) images++;
                            else attachmentNotes.add(att.filename() + ": not a financial document");
                        }
                        case DOCUMENT, INLINE_TEXT -> { }
                    }
                }
                if (images > 0) attachmentNotes.add(images + " small or inline image(s) — logos and signatures, not documents");

                totalTransactionsFound += result.getExtracted();
                duplicatesSkipped += result.getDuplicates();
                conflicts += result.getConflicts();

                switch (result.getOutcome()) {
                    case IMPORTED -> {
                        imported += result.getImported();
                        queuedForReview += result.getQueuedForReview();
                        summaries.addAll(result.getSummaries());
                        steps.add("LLM extraction: " + result.getImported() + " transaction(s) imported"
                            + (result.getQueuedForReview() > 0 ? ", " + result.getQueuedForReview() + " queued for review" : ""));
                        saveProcessed(userId, msgId, "IMPORTED", "IMPORTED",
                            String.join("; ", result.getSummaries()), from, "EmailLLMParserService",
                            subject, EmailLLMParserService.countsOf(result));
                        logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                            .gmailMessageId(msgId).sender(from).subject(subject)
                            .matchedParser("EmailLLMParserService").status("IMPORTED")
                            .itemsImported(result.getImported())
                            .detail(String.join("; ", result.getSummaries()))
                            .pipelineSteps(steps).build());
                    }
                    case REVIEW, UNAVAILABLE -> {
                        // Lines that did import before part of the email failed still count.
                        imported += result.getImported();
                        summaries.addAll(result.getSummaries());
                        queuedForReview += result.getQueuedForReview();
                        steps.add("Sent to review queue: " + result.getDetail());
                        saveProcessed(userId, msgId, "UNKNOWN", "SKIPPED",
                            result.getDetail() != null ? result.getDetail() : "Queued for review", from, "EmailLLMParserService",
                            subject, EmailLLMParserService.countsOf(result));
                        logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                            .gmailMessageId(msgId).sender(from).subject(subject)
                            .matchedParser("EmailLLMParserService").status("REVIEW_REQUIRED")
                            .detail(result.getDetail()).pipelineSteps(steps).build());
                        skipped++;
                    }
                    case NOT_FINANCIAL -> {
                        if (!queuedPdf) {
                            String summary = (result.getDetail() != null && !"Model found no transactions".equals(result.getDetail())
                                ? result.getDetail() : "Read in full; no financial transaction found") + ": " + subject;
                            steps.add(summary);
                            saveProcessed(userId, msgId, NO_TRANSACTION, "SKIPPED", summary, from, null,
                                subject, EmailLLMParserService.countsOf(result));
                            logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                                .gmailMessageId(msgId).sender(from).subject(subject)
                                .status("SKIPPED").detail(summary).pipelineSteps(steps).build());
                        } else {
                            String summary = "PDF queued for unlock: " + subject;
                            saveProcessed(userId, msgId, "UNKNOWN", "SKIPPED", summary, from, "PendingPdf", subject, null);
                            logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                                .gmailMessageId(msgId).sender(from).subject(subject)
                                .matchedParser("PDF").status("PDF_QUEUED").detail(summary)
                                .pipelineSteps(steps).build());
                        }
                        skipped++;
                    }
                }
                manifestService.refresh(userId, msgId, attachments.size(), attachmentsRead, String.join("; ", attachmentNotes));
                } catch (Exception e) {
                    log.error("Failed to process message {} for user {}: {}", msgId, userId, e.getMessage(), e);
                    failed++;
                    saveProcessed(userId, msgId, "ERROR", "FAILED", "Sync error: " + e.getMessage(), null, null, null,
                        com.marketai.gmail.entity.DocumentCounts.builder().outcome(com.marketai.gmail.entity.DocumentCounts.FAILED).build());
                    logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                        .gmailMessageId(msgId).status("FAILED").detail("Sync error: " + e.getMessage())
                        .build());
                }
            }

            // After processing all emails, try to auto-unlock any remaining locked PDFs
            // (covers PDFs from previous syncs that now have a saved password available)
            try {
                int bulkUnlocked = pdfImportService.bulkAutoUnlock(userId);
                if (bulkUnlocked > 0) {
                    // Statements, not transactions: their lines are counted on each PendingPdf.
                    summaries.add("Auto-unlocked " + bulkUnlocked + " previously locked statement(s) using saved passwords");
                }
            } catch (Exception e) {
                log.warn("Bulk auto-unlock failed: {}", e.getMessage());
                actionItems.add("Locked statements could not be retried this run: " + e.getMessage());
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
        } catch (Exception e) {
            log.warn("Could not count locked statements for user {}: {}", userId, e.getMessage());
            actionItems.add("Could not check for statements still waiting for a password: " + e.getMessage());
        }
        if (pendingPdfs > 0) {
            actionItems.add(pendingPdfs + " PDF attachment(s) still awaiting password unlock");
        }
        int unsaved = BOOKKEEPING_FAILURES.get()[0];
        if (unsaved > 0) {
            actionItems.add(unsaved + " email(s) could not be marked as processed and will be read again next sync");
        }
        if (conflicts > 0) {
            actionItems.add(conflicts + " transaction(s) quote a payment reference already recorded with different "
                + "details — see the Reconciliation Center");
        }
        if (queuedForReview > 0) {
            actionItems.add(queuedForReview + " item(s) waiting in the review queue");
        }

        // Coverage: every event read this run, and every one still open from before, must be
        // accounted for before the run can call itself complete.
        long eventsSeen = 0, unresolvedThisRun = 0, outstanding = 0;
        boolean coverageKnown = true;
        try {
            var seen = ledger.seenSince(userId, runStart);
            eventsSeen = seen.values().stream().mapToLong(Long::longValue).sum();
            unresolvedThisRun = com.marketai.gmail.ledger.FinancialEventLedger.unresolvedIn(seen);
            var coverage = ledger.coverage(userId);
            outstanding = coverage.unresolved();
            if (outstanding > 0) {
                actionItems.add(outstanding + " financial event(s) in " + coverage.emailsWithUnresolved()
                    + " email(s) are not yet accounted for — each is listed with its reason under "
                    + "Reconciliation Center → Unresolved email events");
            }
        } catch (Exception e) {
            coverageKnown = false;
            log.warn("Coverage check failed for user {}: {}", userId, e.getMessage());
            actionItems.add("Could not check that every financial event was accounted for: " + e.getMessage());
        }
        int ledgerFailures = com.marketai.gmail.ledger.FinancialEventLedger.failures();
        if (ledgerFailures > 0) {
            actionItems.add(ledgerFailures + " event record(s) could not be saved to the import ledger — the emails "
                + "concerned will be checked again next sync");
        }
        boolean complete = failed == 0 && syncError == null && actionItems.isEmpty() && coverageKnown && outstanding == 0;

        GmailSyncResult.ReconciliationReport reconciliation = GmailSyncResult.ReconciliationReport.builder()
            .emailsProcessed(totalEmails)
            .attachmentsProcessed(totalAttachments)
            .transactionsFound(totalTransactionsFound)
            .transactionsImported(imported)
            .duplicatesSkipped(duplicatesSkipped)
            .failedImports(failed)
            .pdfsPending(pendingPdfs)
            .status(complete ? "OK" : "ACTION_REQUIRED")
            .headline(syncError != null ? "⚠ Sync stopped before finishing"
                : complete ? "✓ Sync Complete" : "⚠ Sync completed with reconciliation required")
            .eventsSeen(eventsSeen)
            .eventsAccounted(eventsSeen - unresolvedThisRun)
            .eventsUnresolved(unresolvedThisRun)
            .outstandingUnresolved(outstanding)
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

    private void recordAttachmentProblem(Long userId, String msgId, GmailClientService.AttachmentInfo att,
                                         String key, String reason) {
        ledger.record(com.marketai.gmail.ledger.EmailFinancialEvent.builder()
            .userId(userId).gmailMessageId(msgId).eventKey(key)
            .sourceKind(com.marketai.gmail.ledger.EmailFinancialEvent.EMAIL)
            .attachmentId(att.attachmentId()).attachmentName(att.filename())
            .eventType("ATTACHMENT").state(com.marketai.gmail.ledger.EventState.RECONCILIATION_REQUIRED)
            .reason(reason).validationStatus("NOT_CHECKED").dedupStatus("NOT_CHECKED")
            .extractedAt(LocalDateTime.now())
            .build());
    }

    // Upsert, not insert: a retried FAILED email already has a row, and (user_id,
    // gmail_message_id) is unique — a blind insert would throw and lose the new outcome.
    private void saveProcessed(Long userId, String msgId, String type, String status, String summary, String sender, String matchedParser) {
        saveProcessed(userId, msgId, type, status, summary, sender, matchedParser, null, null);
    }

    /**
     * @return false when the row could not be written — the email will simply be read again next
     *         time, but the caller records it so the run doesn't report a clean result
     */
    private boolean saveProcessed(Long userId, String msgId, String type, String status, String summary, String sender,
                                  String matchedParser, String subject, com.marketai.gmail.entity.DocumentCounts counts) {
        try {
            ProcessedEmail row = processedRepo.findByUserIdAndGmailMessageId(userId, msgId)
                .orElseGet(() -> ProcessedEmail.builder().userId(userId).gmailMessageId(msgId).build());
            row.setType(type);
            row.setStatus(status);
            row.setMatchedParser(matchedParser);
            row.setSender(sender != null && sender.length() > 320 ? sender.substring(0, 320) : sender);
            row.setResultSummary(summary != null && summary.length() > 900 ? summary.substring(0, 900) : summary);
            row.setProcessedAt(LocalDateTime.now());
            if (subject != null) row.setSubject(subject.length() > 500 ? subject.substring(0, 500) : subject);
            if (counts != null) row.setCounts(counts);
            processedRepo.save(row);
            return true;
        } catch (Exception e) {
            log.warn("Failed to save ProcessedEmail for message {}: {}", msgId, e.getMessage());
            BOOKKEEPING_FAILURES.get()[0]++;
            return false;
        }
    }

    /** ProcessedEmail writes that failed during the current run on this thread (a run is one
     *  thread from start to finish), reported as an action item. */
    private static final ThreadLocal<int[]> BOOKKEEPING_FAILURES = ThreadLocal.withInitial(() -> new int[1]);
}
