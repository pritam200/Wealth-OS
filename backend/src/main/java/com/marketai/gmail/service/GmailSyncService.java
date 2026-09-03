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
    private final ParsedEmailImporter importer;
    private final com.marketai.gmail.ai.AiEmailExtractor aiEmailExtractor;
    private final PendingPdfRepository pendingPdfRepo;
    private final ExcludedSenderRepository excludedSenderRepo;
    private final PasswordHintExtractor passwordHintExtractor;
    private final PdfImportService pdfImportService;

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
            return doSyncForUser(userId, lookbackPeriod);
        } finally {
            lock.unlock();
        }
    }

    private GmailSyncResult doSyncForUser(Long userId, String lookbackPeriod) {
        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        if (token == null) return emptyResult("Gmail not connected — please connect your Gmail account first.");

        User user = userRepo.findById(userId).orElse(null);
        if (user == null) return emptyResult("User account not found.");

        int imported = 0, skipped = 0, failed = 0;
        int totalEmails = 0, totalAttachments = 0, totalTransactionsFound = 0, duplicatesSkipped = 0;
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
                messages = gmailClient.fetchRecentMessages(gmail, 300, lookbackPeriod);
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
                if (processedRepo.existsByUserIdAndGmailMessageId(userId, msgId)) {
                    skipped++;
                    duplicatesSkipped++;
                    continue;
                }

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

                if (parsed.isEmpty() && aiFallbackEnabled) {
                    steps.add("No regex parser matched — trying AI extraction");
                    try {
                        List<ParsedEmail> aiParsed = aiEmailExtractor.extract(from, subject, body);
                        if (!aiParsed.isEmpty()) {
                            parsed.addAll(aiParsed);
                            matchedParser = "AiEmailExtractor (AI)";
                            steps.add("AI extracted " + aiParsed.size() + " item(s)");
                        } else {
                            steps.add("AI found no transactions in email text");
                        }
                    } catch (Exception e) {
                        steps.add("AI fallback failed: " + e.getMessage());
                        log.warn("AI fallback failed on message {}: {}", msgId, e.getMessage());
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

                int emailImported = 0;
                totalTransactionsFound += parsed.size();
                List<String> importedTypes = new ArrayList<>();
                for (ParsedEmail pe : parsed) {
                    try {
                        importer.importParsedEmail(userId, user, pe, msgId);
                        summaries.add(pe.getSourceDescription());
                        importedTypes.add(pe.getType().name());
                        steps.add("DB updated: " + pe.getType().name() + " — " + pe.getSourceDescription());
                        imported++;
                        emailImported++;
                    } catch (Exception e) {
                        steps.add("Import failed for " + pe.getType().name() + ": " + e.getMessage());
                        log.error("Import failed for {}: {}", pe.getSourceDescription(), e.getMessage());
                        actionItems.add("FAILED: " + pe.getSourceDescription() + " — " + e.getMessage());
                        failed++;
                    }
                }

                String combinedType = importedTypes.isEmpty() ? (parsed.isEmpty() ? "UNKNOWN" : parsed.get(0).getType().name()) : String.join(",", importedTypes);
                String combinedSummary = emailImported > 0 ? String.join("; ", summaries.subList(Math.max(0, summaries.size() - emailImported), summaries.size())) : "Import failed";
                saveProcessed(userId, msgId, combinedType, emailImported > 0 ? "IMPORTED" : "FAILED", combinedSummary, from, matchedParser);

                if (emailImported > 0) steps.add("UI refresh triggered");

                String firstType = parsed.isEmpty() ? "UNKNOWN" : parsed.get(0).getType().name();
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

        return GmailSyncResult.builder()
                .imported(imported).skipped(skipped).failed(failed)
                .summaries(summaries).logEntries(logEntries)
                .error(syncError)
                .reconciliation(reconciliation)
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

    private void saveProcessed(Long userId, String msgId, String type, String status, String summary, String sender, String matchedParser) {
        try {
            processedRepo.save(ProcessedEmail.builder()
                    .userId(userId)
                    .gmailMessageId(msgId)
                    .type(type)
                    .status(status)
                    .matchedParser(matchedParser)
                    .sender(sender != null && sender.length() > 320 ? sender.substring(0, 320) : sender)
                    .resultSummary(summary != null && summary.length() > 900 ? summary.substring(0, 900) : summary)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to save ProcessedEmail for message {}: {}", msgId, e.getMessage());
        }
    }
}
