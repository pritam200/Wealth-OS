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
import com.marketai.gmail.parser.EmailParser;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.parser.ParserUtil;
import com.marketai.gmail.repository.ExcludedSenderRepository;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import com.marketai.ai.intel.EmailIntelResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.marketai.document.classify.SenderTrustEvaluator;
import com.marketai.document.route.SelectionComparator;
import com.marketai.document.route.SelectionComparison;
import com.marketai.ai.intel.EmailIntelType;

@Service
@RequiredArgsConstructor
public class GmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncService.class);
    private static final ConcurrentHashMap<Long, ReentrantLock> USER_SYNC_LOCKS = new ConcurrentHashMap<>();

    private final GmailTokenRepository tokenRepo;
    private final ProcessedEmailRepository processedRepo;
    private final GmailClientService gmailClient;
    private final UserRepository userRepo;
    private final List<EmailParser> parsers;
    private final SenderTrustEvaluator senderTrustEvaluator;
    private final SelectionComparator selectionComparator;
    private final ParsedEmailImporter importer;
    // AiEmailExtractor was injected here but never called — the email-body AI fallback is
    // EmailIntelAgent's job now, and the extractor is only used on the PDF path
    // (PdfImportService). Removed rather than left as a misleading dependency.
    private final PendingPdfRepository pendingPdfRepo;
    private final ExcludedSenderRepository excludedSenderRepo;
    private final PasswordHintExtractor passwordHintExtractor;
    private final PdfImportService pdfImportService;
    private final com.marketai.ai.intel.EmailIntelAgent emailIntelAgent;
    private final com.marketai.ai.review.service.EmailReviewService emailReviewService;
    private final com.marketai.ai.llm.LlmProviderRouter llmRouter;

    @Value("${app.gmail.ai-fallback.enabled:true}")
    private boolean aiFallbackEnabled;

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
     * Everything downstream (parsers, dedup, review queue) is identical; only how the message
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
                    if (!"FAILED".equals(existing.getStatus())) {
                        skipped++;
                        duplicatesSkipped++;
                        continue;
                    }
                    // A previous attempt at this email left it FAILED (transient error — parser
                    // exception, DB hiccup). Unlike a genuinely-processed email, this must be
                    // retried on every sync, not permanently skipped — otherwise the underlying
                    // transaction is silently and permanently dropped. Fall through and reprocess;
                    // saveProcessed() below updates this same row rather than inserting a new one
                    // (the (user_id, gmail_message_id) unique constraint would otherwise reject it).
                    log.info("Retrying previously FAILED email {} for user {}", msgId, userId);
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

                boolean isMstock = from != null && (from.toLowerCase().contains("mstock") || from.toLowerCase().contains("miraeasset") || from.toLowerCase().contains("mirae"));
                if (isMstock) {
                    log.info("MSTOCK EMAIL FOUND — from: {}, subject: {}, hasBody: {}, bodyLen: {}",
                        from, subject, body != null, body != null ? body.length() : 0);
                }

                if (!excludedPatterns.isEmpty()
                        && ParserUtil.containsIgnoreCase(from + " " + subject + " " + body, excludedPatterns.toArray(new String[0]))) {
                    steps.add("Matched excluded sender/pattern filter — skipped");
                    saveProcessed(userId, msgId, "EXCLUDED", "SKIPPED", "Matches an excluded sender/pattern filter", from, null);
                    logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                        .gmailMessageId(msgId).sender(from).subject(subject)
                        .status("EXCLUDED").detail("Matches excluded sender filter")
                        .pipelineSteps(steps).build());
                    skipped++;
                    continue;
                }

                List<ParsedEmail> parsed = new ArrayList<>();
                String matchedParser = null;
                for (EmailParser parser : parsers) {
                    try {
                        if (parser.canParse(from, subject)) {
                            String parserName = parser.getClass().getSimpleName();
                            List<ParsedEmail> parserResults = parser.parse(from, subject, body);
                            if (!parserResults.isEmpty()) {
                                matchedParser = parserName;
                                parsed.addAll(parserResults);
                                steps.add("Parser matched: " + matchedParser + " → " + parsed.size() + " item(s)");
                                break;
                            }
                            steps.add("Parser " + parserName + " matched but returned 0 results — trying next");
                        }
                    } catch (Exception e) {
                        steps.add("Parser " + parser.getClass().getSimpleName() + " failed: " + e.getMessage());
                        log.warn("Parser {} failed on message {}: {}", parser.getClass().getSimpleName(), msgId, e.getMessage());
                    }
                }

                // Shadow-mode comparison: run classifier-based routing alongside the legacy
                // selection above and record whether they agree. Neither path can affect the
                // other — this only observes, so a bug here cannot change what gets imported.
                //
                // The point is to gather evidence on real mail before routing is allowed to
                // decide anything. Read the tally at GET /api/gmail/selection-comparison;
                // cutover needs regressions() at zero, not just passing unit tests.
                try {
                    SelectionComparison cmp = selectionComparator.compare(
                        from, subject, body, matchedParser, parsers);
                    if (!cmp.isClean()) {
                        steps.add("Routing shadow: " + cmp.detail());
                    }
                } catch (Exception e) {
                    // Belt and braces. The comparator already swallows its own failures; this
                    // guarantees an observability feature can never break an import.
                    log.debug("Selection comparison skipped: {}", e.toString());
                }

                // Local-LLM classification, only for emails no deterministic parser could read.
                // Kept as a fallback rather than a per-email step on purpose: a local model
                // costs seconds per call, so putting it in front of every message would turn a
                // routine sync into a multi-minute job.
                if (parsed.isEmpty() && aiFallbackEnabled && emailIntelAgent.isEnabled()) {
                    steps.add("No regex parser matched — classifying with " + llmRouter.describeActive());
                    try {
                        EmailIntelResult intel = emailIntelAgent.classify(userId, from, subject, body, msgId);
                        switch (intel.getOutcome()) {
                            case IMPORT:
                                parsed.add(intel.getParsed());
                                matchedParser = "EmailIntelAgent (" + intel.getType() + ")";
                                steps.add(String.format("Classified as %s at %.0f%% confidence", intel.getType(),
                                    intel.getConfidence() != null ? intel.getConfidence() * 100 : 0));
                                break;
                            case REVIEW_REQUIRED:
                            case UNRESOLVED:
                                // Previously this email was dropped with only a log line, so a
                                // real transaction could go missing invisibly. Now it becomes a
                                // row the user can accept, correct or reject.
                                emailReviewService.enqueue(userId, msgId, from, subject, intel);
                                queuedForReview++;
                                steps.add("Sent to review queue: " + intel.getReviewReason());
                                saveProcessed(userId, msgId, intel.getType() != null ? intel.getType().name() : "UNKNOWN",
                                    "REVIEW_REQUIRED", intel.getReviewReason(), from, "EmailIntelAgent");
                                logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                                    .gmailMessageId(msgId).sender(from).subject(subject)
                                    .matchedParser("EmailIntelAgent").status("REVIEW_REQUIRED")
                                    .type(intel.getType() != null ? intel.getType().name() : "UNKNOWN")
                                    .detail(intel.getReviewReason()).pipelineSteps(steps).build());
                                skipped++;
                                continue;
                            case NOT_A_TRANSACTION:
                                steps.add("Classified as non-transactional (" + intel.getType() + ")");
                                break;
                        }
                    } catch (Exception e) {
                        steps.add("AI classification failed: " + e.getMessage());
                        log.warn("AI classification failed on message {}: {}", msgId, e.getMessage());
                    }
                }

                // Check for PDF attachments — even when body parsing found something,
                // also try PDFs if the email has them (contract notes contain detailed
                // trade data only in the PDF, not in the email body summary).
                List<GmailClientService.PdfAttachmentRef> pdfRefs = gmailClient.findPdfAttachments(message);
                if (isMstock) {
                    log.info("MSTOCK PDF CHECK — found {} PDF attachment(s), parsed {} items so far", pdfRefs.size(), parsed.size());
                    for (GmailClientService.PdfAttachmentRef pRef : pdfRefs) {
                        log.info("MSTOCK PDF — filename: {}, attachmentId: {}", pRef.filename, pRef.attachmentId);
                    }
                }
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
                        String hint = passwordHintExtractor.extract(subject, body, providerKey);
                        if (hint == null) hint = pdfImportService.findKnownHint(userId, providerKey);
                        PendingPdf savedPdf = pendingPdfRepo.save(PendingPdf.builder()
                            .userId(userId).gmailMessageId(msgId).attachmentId(pdf.attachmentId)
                            .filename(pdf.filename).sender(from).subject(subject)
                            .providerKey(providerKey)
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

                if (parsed.isEmpty() && !queuedPdf) {
                    String summary = matchedParser != null
                        ? matchedParser + " matched but recorded nothing: " + subject
                        : "No parser matched and no PDF attachment: " + subject;
                    steps.add(summary);
                    saveProcessed(userId, msgId, "UNKNOWN", "SKIPPED", summary, from, matchedParser);
                    logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                        .gmailMessageId(msgId).sender(from).subject(subject)
                        .matchedParser(matchedParser).status("SKIPPED").detail(summary)
                        .pipelineSteps(steps).build());
                    skipped++;
                    continue;
                }

                if (parsed.isEmpty() && queuedPdf) {
                    String summary = "PDF queued for unlock: " + subject;
                    saveProcessed(userId, msgId, "UNKNOWN", "SKIPPED", summary, from, "PendingPdf");
                    logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                        .gmailMessageId(msgId).sender(from).subject(subject)
                        .matchedParser("PDF").status("PDF_QUEUED").detail(summary)
                        .pipelineSteps(steps).build());
                    skipped++;
                    continue;
                }

                // Sender authority check, placed after parsing so a held-back email still
                // reaches the review queue with its extracted content rather than as a bare
                // "something was blocked" row.
                //
                // Why it is needed: parser selection above matches substrings against `from`,
                // which is the raw From header including the display name — and the display
                // name is chosen by whoever sent the message. So
                // `"Zerodha Alerts" <noreply@attacker.example>` satisfies
                // containsIgnoreCase(from, "zerodha") and is handed to ZerodhaParser, whose
                // output would then flow to the ledger.
                //
                // Scope is deliberately narrow. Only the impersonation shape is held back —
                // a display name naming an issuer the sending domain cannot support. An
                // unrecognised sender making no such claim still imports normally, because the
                // issuer registry will always lag reality and blocking those would silently
                // drop real transactions from any bank not yet catalogued.
                SenderTrustEvaluator.Assessment trust = senderTrustEvaluator.evaluate(from);
                if (!trust.permitsAutoImport()) {
                    steps.add("Sender authority check failed: " + trust.detail());
                    log.warn("Blocked auto-import for user {} message {}: {}", userId, msgId, trust.detail());

                    // Nothing is dropped. Each parsed item becomes a review row the user can
                    // accept if the sender is in fact legitimate.
                    for (ParsedEmail pe : parsed) {
                        emailReviewService.enqueue(userId, msgId, from, subject,
                            EmailIntelResult.builder()
                                .outcome(EmailIntelResult.Outcome.REVIEW_REQUIRED)
                                .type(EmailIntelType.UNKNOWN)
                                .parsed(pe)
                                .reviewReason("Sender could not be verified — " + trust.detail())
                                .reasoning("Held back by the sender authority check rather than the "
                                    + "classifier. " + matchedParser + " read this email, but the "
                                    + "sending domain does not belong to the issuer the header claims. "
                                    + "Accept only if you recognise this sender as genuine.")
                                .build());
                        queuedForReview++;
                    }
                    saveProcessed(userId, msgId, "REVIEW", "SKIPPED",
                        "Sender authority check failed: " + trust.detail(), from, matchedParser);
                    logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                        .gmailMessageId(msgId).sender(from).subject(subject)
                        .matchedParser(matchedParser).status("REVIEW")
                        .detail("Sender authority check failed: " + trust.detail())
                        .pipelineSteps(steps).build());
                    continue;
                }
                if (trust.trust() == com.marketai.document.classify.SenderTrust.UNKNOWN_DOMAIN) {
                    // Recorded, not blocked — useful signal for deciding what to add to the
                    // registry, and visible in the sync log without costing the user an import.
                    steps.add("Sender domain not in the issuer registry (imported anyway)");
                }

                int emailImported = 0;
                totalTransactionsFound += parsed.size();
                List<String> importedTypes = new ArrayList<>();
                for (ParsedEmail pe : parsed) {
                    try {
                        importer.importParsedEmail(userId, user, pe, msgId, body);
                        summaries.add(pe.getSourceDescription());
                        importedTypes.add(typeName(pe));
                        steps.add("DB updated: " + typeName(pe) + " — " + pe.getSourceDescription());
                        imported++;
                        emailImported++;
                    } catch (Exception e) {
                        steps.add("Import failed for " + typeName(pe) + ": " + e.getMessage());
                        log.error("Import failed for {}: {}", pe.getSourceDescription(), e.getMessage());
                        actionItems.add("FAILED: " + pe.getSourceDescription() + " — " + e.getMessage());
                        failed++;
                    }
                }

                String combinedType = importedTypes.isEmpty() ? (parsed.isEmpty() ? "UNKNOWN" : typeName(parsed.get(0))) : String.join(",", importedTypes);
                String combinedSummary = emailImported > 0 ? String.join("; ", summaries.subList(Math.max(0, summaries.size() - emailImported), summaries.size())) : "Import failed";
                saveProcessed(userId, msgId, combinedType, emailImported > 0 ? "IMPORTED" : "FAILED", combinedSummary, from, matchedParser);

                if (emailImported > 0) steps.add("UI refresh triggered");

                String firstType = parsed.isEmpty() ? "UNKNOWN" : typeName(parsed.get(0));
                logEntries.add(GmailSyncResult.SyncLogEntry.builder()
                    .gmailMessageId(msgId).sender(from).subject(subject)
                    .matchedParser(matchedParser)
                    .status(emailImported > 0 ? "IMPORTED" : "FAILED")
                    .type(firstType)
                    .detail(emailImported > 0 ? parsed.get(0).getSourceDescription() : "Import failed")
                    .itemsImported(emailImported)
                    .pipelineSteps(steps).build());
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

    /**
     * A parsed item's type as a log label.
     *
     * <p>Null-safe because {@code ParsedEmailImporter.routeImport} documents a null type as
     * reachable and handles it — but the log lines here dereferenced it, so an item the importer
     * had already committed threw NPE on the way to being logged and the message was recorded as
     * FAILED. A logging concern must not change a record's reported outcome.
     */
    private static String typeName(ParsedEmail pe) {
        return pe == null || pe.getType() == null ? "UNKNOWN" : pe.getType().name();
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
