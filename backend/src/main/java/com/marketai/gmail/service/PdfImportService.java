package com.marketai.gmail.service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.marketai.auth.entity.User;
import com.marketai.auth.repository.UserRepository;
import com.marketai.gmail.ai.AiEmailExtractor;
import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.entity.SavedPdfPassword;
import com.marketai.gmail.parser.EmailParser;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.repository.PendingPdfRepository;
import com.marketai.gmail.repository.SavedPdfPasswordRepository;
import com.marketai.gmail.security.PasswordCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decrypts a password-protected PDF (broker contract note, margin/account statement) and
 * runs its extracted text through the exact same parser chain + AI fallback that normal
 * email bodies go through.
 *
 * On a successful manual unlock, the password is encrypted (AES-GCM, see PasswordCipher)
 * and saved per sender-domain (providerKey) — future statements from the same institution
 * are unlocked automatically via {@link #tryAutoUnlock}, called right after a new PendingPdf
 * is queued in GmailSyncService. A saved password is only ever decrypted in-memory for a
 * single PDFBox.load() call; the plaintext is never logged or exposed via any endpoint.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PdfImportService {

    private static final List<String> PENDING_STATUSES = Arrays.asList("NEEDS_PASSWORD", "PASSWORD_FAILED");
    private static final List<String> RETRYABLE_STATUSES = Arrays.asList("NEEDS_PASSWORD", "PASSWORD_FAILED", "FAILED");

    // Brokers, exchanges, and RTAs in India that all use PAN (uppercase) as the standard
    // statement password. When a PAN password is saved for any one of these, it's reused
    // across all of them — the user only needs to enter their PAN once.
    private static final Set<String> PAN_PASSWORD_DOMAINS = new HashSet<>(Arrays.asList(
        "camsonline.com", "mstock.com", "nse.co.in", "kfintech.com",
        "nsdl.co.in", "nsdl.com", "cdslindia.com", "bseindia.com",
        "zerodha.com", "upstox.com", "angelbroking.com", "angelone.in", "groww.in",
        "icicidirect.com", "hdfcsec.com", "kotaksecurities.com", "sbisecurities.in",
        "indiainfoline.com", "5paisa.com", "sharekhan.com",
        "axisdirect.in", "motilaloswal.com", "sbimf.com", "hdfcfund.com",
        "iciciprumf.com", "utimf.com", "nipponindiamf.com", "dfrankfurt.com",
        "franklintempletonindia.com", "dspmf.com", "tatamf.com",
        "motilaloswal.com", "aboretummf.com", "bfranklintempletonind.com"
    ));

    private final PendingPdfRepository pendingPdfRepo;
    private final SavedPdfPasswordRepository savedPasswordRepo;
    private final GmailTokenRepository tokenRepo;
    private final UserRepository userRepo;
    private final GmailClientService gmailClient;
    private final List<EmailParser> parsers;
    private final AiEmailExtractor aiEmailExtractor;
    private final ParsedEmailImporter importer;
    private final PasswordCipher passwordCipher;
    private final PasswordHintExtractor passwordHintExtractor;

    public List<PendingPdf> list(Long userId) {
        List<PendingPdf> items = pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, PENDING_STATUSES);
        backfillHints(userId, items);
        // Auto-unlock is NOT done here — it's too slow for a synchronous GET (downloads
        // PDFs from Gmail, attempts decryption). It runs during sync and bulkAutoUnlock instead.
        return items;
    }

    private void retryAutoUnlock(Long userId, List<PendingPdf> items) {
        for (PendingPdf pdf : items) {
            if (!"NEEDS_PASSWORD".equals(pdf.getStatus()) && !"PASSWORD_FAILED".equals(pdf.getStatus())) continue;
            if (pdf.getProviderKey() == null) continue;
            try {
                tryAutoUnlock(userId, pdf);
            } catch (Exception e) {
                log.debug("Retry auto-unlock failed for pending {}: {}", pdf.getId(), e.getMessage());
            }
        }
    }

    public int bulkAutoUnlock(Long userId) {
        List<PendingPdf> locked = pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, RETRYABLE_STATUSES);
        int unlocked = 0;
        for (PendingPdf pdf : locked) {
            if (pdf.getProviderKey() == null) continue;
            try {
                tryAutoUnlock(userId, pdf);
                PendingPdf refreshed = pendingPdfRepo.findById(pdf.getId()).orElse(pdf);
                if ("IMPORTED".equals(refreshed.getStatus())) unlocked++;
            } catch (Exception e) {
                log.debug("Bulk auto-unlock failed for pending {}: {}", pdf.getId(), e.getMessage());
            }
        }
        if (unlocked > 0) log.info("Bulk auto-unlock: unlocked {} of {} locked statements for user {}", unlocked, locked.size(), userId);
        return unlocked;
    }

    // Once any statement from a provider has a known password-format hint (either from a
    // saved password's hint, or from another PendingPdf we already extracted it for), reuse
    // it instead of re-guessing from scratch for every new statement from that same sender.
    public String findKnownHint(Long userId, String providerKey) {
        if (providerKey == null) return null;
        String fromSaved = savedPasswordRepo.findByUserIdAndProviderKey(userId, providerKey)
            .map(SavedPdfPassword::getPasswordHint)
            .filter(h -> h != null)
            .orElse(null);
        if (fromSaved != null) return fromSaved;
        return pendingPdfRepo.findFirstByUserIdAndProviderKeyAndPasswordHintIsNotNull(userId, providerKey)
            .map(PendingPdf::getPasswordHint)
            .orElse(null);
    }

    // Rows queued before the password-hint/auto-reuse feature existed have no providerKey
    // or passwordHint (that email body text was never persisted). Recompute them lazily on
    // read instead of leaving old statements permanently stuck showing "format unknown" —
    // providerKey is free (derived from the stored sender), passwordHint needs one Gmail
    // API call per legacy row to re-read the body text.
    // Hints that were stored by earlier buggy extraction logic (e.g. "Folio number" or
    // "Customer ID" when the email actually says PAN). Instead of maintaining an ever-growing
    // list, backfillHints always re-extracts from the email body and replaces the stored hint
    // whenever the fresh result differs — so improving PasswordHintExtractor automatically
    // fixes all previously-wrong rows on the next list() call.

    private void backfillHints(Long userId, List<PendingPdf> items) {
        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        Gmail gmail = null;
        if (token != null) {
            try { gmail = gmailClient.buildGmailService(token.getAccessToken(), token.getRefreshToken()); }
            catch (Exception e) { log.warn("Could not build Gmail client for hint backfill: {}", e.getMessage()); }
        }
        for (PendingPdf pdf : items) {
            boolean changed = false;
            if (pdf.getProviderKey() == null && pdf.getSender() != null) {
                pdf.setProviderKey(providerKeyOf(pdf.getSender()));
                changed = true;
            }
            // Always re-extract from the email body so improved extraction logic
            // automatically corrects previously-wrong hints.
            if (gmail != null) {
                try {
                    Message message = gmailClient.getMessage(gmail, pdf.getGmailMessageId());
                    String body = gmailClient.getBodyText(message);
                    String fresh = passwordHintExtractor.extract(pdf.getSubject(), body, pdf.getProviderKey());
                    if (fresh != null && !fresh.equals(pdf.getPasswordHint())) {
                        pdf.setPasswordHint(fresh);
                        changed = true;
                        // Also fix the SavedPdfPassword hint if it was wrong
                        if (pdf.getProviderKey() != null) {
                            savedPasswordRepo.findByUserIdAndProviderKey(userId, pdf.getProviderKey())
                                .filter(sp -> sp.getPasswordHint() == null || !fresh.equals(sp.getPasswordHint()))
                                .ifPresent(sp -> { sp.setPasswordHint(fresh); savedPasswordRepo.save(sp); });
                        }
                    }
                } catch (Exception e) {
                    log.debug("Hint backfill failed for pending {}: {}", pdf.getId(), e.getMessage());
                }
            }
            // Fallback: if we couldn't reach Gmail, at least try known defaults
            if (pdf.getPasswordHint() == null && pdf.getProviderKey() != null) {
                String known = findKnownHint(userId, pdf.getProviderKey());
                if (known != null) { pdf.setPasswordHint(known); changed = true; }
            }
            if (changed) pendingPdfRepo.save(pdf);
        }
    }

    private static String providerKeyOf(String from) {
        if (from == null) return null;
        int at = from.lastIndexOf('@');
        if (at < 0) return null;
        String domain = from.substring(at + 1).replaceAll("[>\\s]", "").toLowerCase();
        return domain.isEmpty() ? null : domain;
    }

    @Transactional
    public void dismiss(Long userId, Long pendingId) {
        PendingPdf pdf = pendingPdfRepo.findByIdAndUserId(pendingId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        pdf.setStatus("DISMISSED");
        pendingPdfRepo.save(pdf);
    }

    @Transactional
    public void tryAutoUnlock(Long userId, PendingPdf pdf) {
        if (pdf.getProviderKey() == null) return;

        // 1. Try exact-match: saved password for this specific sender domain
        SavedPdfPassword saved = savedPasswordRepo.findByUserIdAndProviderKey(userId, pdf.getProviderKey()).orElse(null);

        // 2. Cross-provider PAN sharing: try ALL saved PAN-domain passwords (multi-PAN households)
        List<SavedPdfPassword> panCandidates = new ArrayList<>();
        if (saved == null && PAN_PASSWORD_DOMAINS.contains(pdf.getProviderKey())) {
            List<SavedPdfPassword> allPasswords = savedPasswordRepo.findByUserIdOrderByUpdatedAtDescCreatedAtDesc(userId);
            for (SavedPdfPassword sp : allPasswords) {
                if (PAN_PASSWORD_DOMAINS.contains(sp.getProviderKey())) {
                    panCandidates.add(sp);
                }
            }
            if (!panCandidates.isEmpty()) {
                saved = panCandidates.get(0);
                log.info("Cross-provider PAN password: trying {} candidate(s) for {}", panCandidates.size(), pdf.getProviderKey());
            }
        }

        // 3. Hint-based sharing: if the password hint for this PDF matches a saved password's hint
        if (saved == null && pdf.getPasswordHint() != null && pdf.getPasswordHint().toLowerCase().contains("pan")) {
            List<SavedPdfPassword> allPasswords = savedPasswordRepo.findByUserIdOrderByUpdatedAtDescCreatedAtDesc(userId);
            for (SavedPdfPassword sp : allPasswords) {
                if (sp.getPasswordHint() != null && sp.getPasswordHint().toLowerCase().contains("pan")) {
                    saved = sp;
                    log.info("Hint-based password sharing: using {} password (hint: {}) for {}", sp.getProviderKey(), sp.getPasswordHint(), pdf.getProviderKey());
                    break;
                }
            }
        }

        if (saved == null) return;

        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        User user = userRepo.findById(userId).orElse(null);
        if (token == null || user == null) return;

        List<SavedPdfPassword> toTry = new ArrayList<>();
        toTry.add(saved);
        for (SavedPdfPassword pc : panCandidates) {
            if (!pc.getId().equals(saved.getId())) toTry.add(pc);
        }

        try {
            Gmail gmail = gmailClient.buildGmailService(token.getAccessToken(), token.getRefreshToken());
            for (SavedPdfPassword candidate : toTry) {
                String password;
                try { password = passwordCipher.decrypt(candidate.getEncryptedPassword()); }
                catch (Exception e) {
                    log.warn("Deleting corrupted saved password for provider {} (encryption key changed): {}", candidate.getProviderKey(), e.getMessage());
                    savedPasswordRepo.delete(candidate);
                    continue;
                }

                PdfUnlockResult result = attemptUnlock(userId, pdf, user, gmail, password, true);
                if (result.unlocked) {
                    candidate.setLastUsedAt(LocalDateTime.now());
                    savedPasswordRepo.save(candidate);
                    if (!candidate.getProviderKey().equals(pdf.getProviderKey())) {
                        SavedPdfPassword providerCopy = savedPasswordRepo.findByUserIdAndProviderKey(userId, pdf.getProviderKey()).orElse(null);
                        if (providerCopy == null) {
                            savedPasswordRepo.save(SavedPdfPassword.builder()
                                .userId(userId).providerKey(pdf.getProviderKey())
                                .encryptedPassword(candidate.getEncryptedPassword())
                                .passwordHint(pdf.getPasswordHint() != null ? pdf.getPasswordHint() : candidate.getPasswordHint())
                                .lastUsedAt(LocalDateTime.now())
                                .build());
                        }
                    }
                    String source = candidate.getProviderKey().equals(pdf.getProviderKey())
                        ? "saved password" : "PAN password (shared from " + candidate.getProviderKey() + ")";
                    pdf.setResultSummary(truncate("Auto-unlocked using " + source + " — " +
                        (pdf.getResultSummary() != null ? pdf.getResultSummary() : "")));
                    pendingPdfRepo.save(pdf);
                    return;
                }
            }
            String reason = "None of " + toTry.size() + " saved password(s) worked for " + pdf.getProviderKey();
            pdf.setResultSummary(truncate(reason));
            pendingPdfRepo.save(pdf);
        } catch (Exception e) {
            log.warn("Auto-unlock attempt failed for pending {}: {}", pdf.getId(), e.getMessage());
        }
    }

    @Transactional
    public PdfUnlockResult unlock(Long userId, Long pendingId, String password) {
        PendingPdf pdf = pendingPdfRepo.findByIdAndUserId(pendingId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        GmailToken token = tokenRepo.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gmail not connected"));
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Gmail gmail;
        try { gmail = gmailClient.buildGmailService(token.getAccessToken(), token.getRefreshToken()); }
        catch (Exception e) { return new PdfUnlockResult(false, "Could not reach Gmail: " + e.getMessage()); }

        PdfUnlockResult result = attemptUnlock(userId, pdf, user, gmail, password, false);

        if (result.unlocked && pdf.getProviderKey() != null) {
            String encrypted = passwordCipher.encrypt(password);
            SavedPdfPassword saved = savedPasswordRepo.findByUserIdAndProviderKey(userId, pdf.getProviderKey())
                .orElse(SavedPdfPassword.builder().userId(userId).providerKey(pdf.getProviderKey()).build());
            saved.setEncryptedPassword(encrypted);
            saved.setPasswordHint(pdf.getPasswordHint());
            saved.setUpdatedAt(LocalDateTime.now());
            saved.setLastUsedAt(LocalDateTime.now());
            savedPasswordRepo.save(saved);

            // Auto-unlock all other pending statements — same provider AND cross-provider PAN group
            int autoUnlocked = 0;
            List<PendingPdf> allPending = pendingPdfRepo.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, PENDING_STATUSES);
            boolean isPanPassword = PAN_PASSWORD_DOMAINS.contains(pdf.getProviderKey());
            for (PendingPdf sibling : allPending) {
                if (sibling.getId().equals(pdf.getId())) continue;
                boolean sameProvider = pdf.getProviderKey().equals(sibling.getProviderKey());
                boolean panCrossProvider = isPanPassword && sibling.getProviderKey() != null
                    && PAN_PASSWORD_DOMAINS.contains(sibling.getProviderKey());
                if (!sameProvider && !panCrossProvider) continue;
                try {
                    PdfUnlockResult sibResult = attemptUnlock(userId, sibling, user, gmail, password, true);
                    if (sibResult.unlocked) {
                        autoUnlocked++;
                        // Save password for the sibling's provider too
                        if (!sameProvider && sibling.getProviderKey() != null) {
                            SavedPdfPassword sibSaved = savedPasswordRepo.findByUserIdAndProviderKey(userId, sibling.getProviderKey()).orElse(null);
                            if (sibSaved == null) {
                                savedPasswordRepo.save(SavedPdfPassword.builder()
                                    .userId(userId).providerKey(sibling.getProviderKey())
                                    .encryptedPassword(encrypted)
                                    .passwordHint(sibling.getPasswordHint() != null ? sibling.getPasswordHint() : pdf.getPasswordHint())
                                    .lastUsedAt(LocalDateTime.now())
                                    .build());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Sibling auto-unlock failed for {}: {}", sibling.getId(), e.getMessage());
                }
            }
            String msg = result.message + " Password saved for future statements.";
            if (autoUnlocked > 0) msg += " Also unlocked " + autoUnlocked + " other statement(s)" + (isPanPassword ? " across providers (shared PAN)" : " from the same sender") + ".";
            return new PdfUnlockResult(true, msg);
        }
        return result;
    }

    // Core decrypt-extract-parse-import flow, shared by the manual (unlock) and automatic
    // (tryAutoUnlock) paths. isAutoAttempt controls what happens on a wrong password: the
    // manual path leaves the item as-is for the user to retry; the automatic path marks it
    // PASSWORD_FAILED so the "Locked statements" list surfaces that the saved password broke.
    private PdfUnlockResult attemptUnlock(Long userId, PendingPdf pdf, User user, Gmail gmail, String password, boolean isAutoAttempt) {
        List<String> steps = new ArrayList<>();
        steps.add(step("email_detected", "OK", "Email: " + truncate(pdf.getSubject(), 80)));
        steps.add(step("attachment_detected", "OK", "File: " + pdf.getFilename()));

        // Step: password
        steps.add(step("password_found", "OK", isAutoAttempt ? "Using saved password" : "User-provided password"));

        // Step: download + decrypt
        String text;
        try {
            byte[] bytes = gmailClient.downloadAttachment(gmail, pdf.getGmailMessageId(), pdf.getAttachmentId());
            steps.add(step("pdf_downloaded", "OK", bytes.length + " bytes"));
            try (PDDocument doc = PDDocument.load(bytes, password)) {
                text = new PDFTextStripper().getText(doc);
            }
            steps.add(step("pdf_unlocked", "OK", text.length() + " chars extracted"));
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            steps.add(step("pdf_unlocked", "FAIL", "Incorrect password"));
            pdf.setPipelineSteps(toJson(steps));
            if (isAutoAttempt) {
                pdf.setStatus("PASSWORD_FAILED");
                pdf.setResultSummary("Saved password failed — please update it.");
                pendingPdfRepo.save(pdf);
            }
            return new PdfUnlockResult(false, "Incorrect password — the statement stays queued, try again.");
        } catch (Exception e) {
            steps.add(step("pdf_unlocked", "FAIL", "Error: " + e.getMessage()));
            pdf.setPipelineSteps(toJson(steps));
            log.warn("PDF unlock/extract failed for pending {}: {}", pdf.getId(), e.getMessage());
            pdf.setStatus("FAILED");
            pdf.setResultSummary(truncate("Could not read PDF: " + e.getMessage()));
            pendingPdfRepo.save(pdf);
            return new PdfUnlockResult(false, "Could not read this PDF: " + e.getMessage());
        }

        // Save more text for debugging — 4000 chars covers the trade section in most contract notes
        pdf.setTextSnippet(truncate(text, 4000));
        pdf.setUnlockedAt(LocalDateTime.now());

        // Step: parse — try all matching parsers (not just the first match)
        log.info("PDF PARSE START — file: {}, sender: {}, subject: {}, textLen: {}, first200: [{}]",
            pdf.getFilename(), pdf.getSender(), pdf.getSubject(), text.length(),
            text.substring(0, Math.min(200, text.length())).replace("\n", " | "));

        List<ParsedEmail> parsed = new ArrayList<>();
        String matchedParserName = null;
        for (EmailParser parser : parsers) {
            String pName = parser.getClass().getSimpleName();
            boolean canParse = false;
            try {
                canParse = parser.canParse(pdf.getSender(), pdf.getSubject());
                log.info("PDF PARSER CHECK — {} canParse={} for sender={}, subject={}",
                    pName, canParse, pdf.getSender(), pdf.getSubject());
                if (canParse) {
                    List<ParsedEmail> parserResult = parser.parse(pdf.getSender(), pdf.getSubject(), text);
                    log.info("PDF PARSER RESULT — {} returned {} trade(s)", pName, parserResult.size());
                    if (!parserResult.isEmpty()) {
                        parsed.addAll(parserResult);
                        matchedParserName = pName;
                        steps.add(step("trades_extracted", "OK",
                            matchedParserName + " found " + parserResult.size() + " trade(s)"));
                        for (ParsedEmail pe : parserResult) {
                            log.info("PDF TRADE — {} {} x {} @ {} on {}",
                                pe.getType(), pe.getSymbol(), pe.getQuantity(), pe.getPrice(), pe.getTradeDate());
                        }
                        break;
                    } else {
                        steps.add(step("parser_tried", "EMPTY",
                            pName + " matched but found 0 trades"));
                    }
                }
            } catch (Exception e) {
                steps.add(step("parser_tried", "ERROR",
                    pName + ": " + e.getMessage()));
                log.warn("Parser {} failed on PDF {}: {}", pName, pdf.getFilename(), e.getMessage());
            }
        }

        // Direct ContractNoteHelper fallback — if no parser matched via canParse,
        // try parsing the raw PDF text as a contract note anyway (the PDF is already
        // unlocked, so we know it's a financial document from a broker)
        if (parsed.isEmpty()) {
            log.info("PDF CN FALLBACK — no parser matched, trying ContractNoteHelper directly");
            try {
                String broker = pdf.getProviderKey() != null ? pdf.getProviderKey().replace(".com","").replace(".in","") : "Broker";
                List<ParsedEmail> cnResult = com.marketai.gmail.parser.ContractNoteHelper.parseContractNoteRows(text, pdf.getSubject(), broker);
                if (!cnResult.isEmpty()) {
                    parsed.addAll(cnResult);
                    matchedParserName = "ContractNoteHelper (direct)";
                    steps.add(step("trades_extracted", "OK",
                        "Direct CN parser found " + cnResult.size() + " trade(s)"));
                    for (ParsedEmail pe : cnResult) {
                        log.info("PDF CN TRADE — {} {} x {} @ {} on {}",
                            pe.getType(), pe.getSymbol(), pe.getQuantity(), pe.getPrice(), pe.getTradeDate());
                    }
                } else {
                    steps.add(step("parser_tried", "EMPTY",
                        "ContractNoteHelper direct parse found 0 trades in " + text.length() + " chars"));
                    log.info("PDF CN FALLBACK — found 0 trades. Dumping lines with B/S for debug:");
                    for (String line : text.split("\\n")) {
                        String t = line.trim();
                        if (t.length() >= 10 && (t.contains(" B ") || t.contains(" S ") || t.toUpperCase().contains("BUY") || t.toUpperCase().contains("SELL"))) {
                            log.info("PDF CN LINE — [{}]", t);
                        }
                    }
                }
            } catch (Exception e) {
                steps.add(step("parser_tried", "ERROR", "ContractNoteHelper direct: " + e.getMessage()));
                log.warn("Direct ContractNoteHelper failed: {}", e.getMessage());
            }
        }

        // AI fallback
        if (parsed.isEmpty()) {
            try {
                List<ParsedEmail> aiResult = aiEmailExtractor.extract(pdf.getSender(), pdf.getSubject(), text);
                if (!aiResult.isEmpty()) {
                    parsed.addAll(aiResult);
                    matchedParserName = "AI Extractor";
                    steps.add(step("trades_extracted", "OK",
                        "AI fallback found " + aiResult.size() + " trade(s)"));
                } else {
                    steps.add(step("trades_extracted", "FAIL",
                        "AI fallback also found 0 trades in " + text.length() + " chars of text"));
                }
            } catch (Exception e) {
                steps.add(step("trades_extracted", "FAIL",
                    "AI extraction failed: " + e.getMessage()));
                log.debug("AI extraction on PDF text failed: {}", e.getMessage());
            }
        }

        pdf.setTradesExtracted(parsed.size());

        if (parsed.isEmpty()) {
            steps.add(step("holdings_updated", "SKIP", "No trades to import"));
            pdf.setPipelineSteps(toJson(steps));
            pdf.setStatus("FAILED");
            pdf.setResultSummary("Unlocked successfully but no transactions were found in the statement text.");
            pendingPdfRepo.save(pdf);
            return new PdfUnlockResult(true, "Unlocked, but no transactions could be identified in this statement.");
        }

        // Step: import trades into portfolio
        int imported = 0, failedCount = 0;
        List<String> summaries = new ArrayList<>();
        for (ParsedEmail pe : parsed) {
            try {
                importer.importParsedEmail(userId, user, pe, pdf.getGmailMessageId());
                summaries.add(pe.getSourceDescription());
                imported++;
            } catch (Exception e) {
                failedCount++;
                log.warn("Import from PDF failed for {}: {}", pe.getSourceDescription(), e.getMessage());
            }
        }

        pdf.setTradesImported(imported);

        if (imported > 0) {
            steps.add(step("holdings_updated", "OK",
                imported + " trade(s) imported" + (failedCount > 0 ? ", " + failedCount + " failed" : "")));
            steps.add(step("dashboard_updated", "OK", "Portfolio recalculated"));
        } else {
            steps.add(step("holdings_updated", "FAIL",
                "All " + parsed.size() + " trade(s) failed to import (duplicates or errors)"));
        }

        pdf.setPipelineSteps(toJson(steps));

        if (imported > 0 && failedCount == 0) {
            pdf.setStatus("IMPORTED");
        } else if (imported > 0) {
            pdf.setStatus("IMPORTED");
            log.warn("Partial import for PDF {}: {} imported, {} failed", pdf.getFilename(), imported, failedCount);
        } else {
            pdf.setStatus("FAILED");
        }
        String summary = imported + " item(s) imported" + (failedCount > 0 ? ", " + failedCount + " failed" : "") + ": " + String.join("; ", summaries);
        pdf.setResultSummary(truncate(summary));
        pendingPdfRepo.save(pdf);
        return new PdfUnlockResult(true, imported + " item(s) imported from " + pdf.getFilename());
    }

    private static String step(String name, String status, String detail) {
        return "{\"step\":\"" + name + "\",\"status\":\"" + status + "\",\"detail\":\"" + escapeJson(detail) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    private static String toJson(List<String> steps) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(steps.get(i));
        }
        sb.append("]");
        String result = sb.toString();
        return result.length() > 4000 ? result.substring(0, 4000) : result;
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    @Transactional
    public void resetFailedPasswords(Long userId) {
        List<PendingPdf> failed = pendingPdfRepo.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "PASSWORD_FAILED");
        java.util.Set<String> clearedProviders = new java.util.HashSet<>();
        for (PendingPdf pdf : failed) {
            pdf.setStatus("NEEDS_PASSWORD");
            pdf.setResultSummary(null);
            pendingPdfRepo.save(pdf);
            String provider = pdf.getSender() != null ? providerKeyOf(pdf.getSender()) : null;
            if (provider != null) clearedProviders.add(provider);
        }
        for (String provider : clearedProviders) {
            savedPasswordRepo.findByUserIdAndProviderKey(userId, provider).ifPresent(sp -> {
                savedPasswordRepo.delete(sp);
                log.info("Deleted broken saved password for provider {} (user {})", provider, userId);
            });
        }
        if (!failed.isEmpty()) log.info("Reset {} PASSWORD_FAILED PDFs back to NEEDS_PASSWORD for user {}", failed.size(), userId);
    }

    @Transactional
    public void resetFailedPdfs(Long userId) {
        List<PendingPdf> failed = pendingPdfRepo.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "FAILED");
        for (PendingPdf pdf : failed) {
            pdf.setStatus("NEEDS_PASSWORD");
            pdf.setResultSummary(null);
            pdf.setPipelineSteps(null);
            pdf.setTradesExtracted(null);
            pdf.setTradesImported(null);
            pdf.setTextSnippet(null);
            pendingPdfRepo.save(pdf);
        }
        if (!failed.isEmpty()) log.info("Reset {} FAILED PDFs back to NEEDS_PASSWORD for re-processing (user {})", failed.size(), userId);
    }

    public List<PendingPdf> listAll(Long userId) {
        return pendingPdfRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<SavedPdfPassword> listSavedPasswords(Long userId) {
        return savedPasswordRepo.findByUserIdOrderByUpdatedAtDescCreatedAtDesc(userId);
    }

    @Transactional
    public void updateSavedPassword(Long userId, Long id, String newPassword) {
        SavedPdfPassword saved = savedPasswordRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        saved.setEncryptedPassword(passwordCipher.encrypt(newPassword));
        saved.setUpdatedAt(LocalDateTime.now());
        savedPasswordRepo.save(saved);
    }

    @Transactional
    public void deleteSavedPassword(Long userId, Long id) {
        SavedPdfPassword saved = savedPasswordRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        savedPasswordRepo.delete(saved);
    }

    private String truncate(String s) {
        return truncate(s, 490);
    }

    public java.util.Map<String, Object> debugPdf(Long userId, Long pendingId) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        PendingPdf pdf = pendingPdfRepo.findByIdAndUserId(pendingId, userId).orElse(null);
        if (pdf == null) { result.put("error", "PendingPdf not found"); return result; }

        result.put("id", pdf.getId());
        result.put("filename", pdf.getFilename());
        result.put("sender", pdf.getSender());
        result.put("subject", pdf.getSubject());
        result.put("providerKey", pdf.getProviderKey());
        result.put("status", pdf.getStatus());

        GmailToken token = tokenRepo.findByUserId(userId).orElse(null);
        if (token == null) { result.put("error", "No Gmail token"); return result; }

        SavedPdfPassword saved = savedPasswordRepo.findByUserIdAndProviderKey(userId, pdf.getProviderKey()).orElse(null);
        if (saved == null && PAN_PASSWORD_DOMAINS.contains(pdf.getProviderKey())) {
            for (SavedPdfPassword sp : savedPasswordRepo.findByUserIdOrderByUpdatedAtDescCreatedAtDesc(userId)) {
                if (PAN_PASSWORD_DOMAINS.contains(sp.getProviderKey())) { saved = sp; break; }
            }
        }
        if (saved == null) { result.put("error", "No saved password found"); return result; }

        result.put("passwordProvider", saved.getProviderKey());

        try {
            Gmail gmail = gmailClient.buildGmailService(token.getAccessToken(), token.getRefreshToken());
            String password = passwordCipher.decrypt(saved.getEncryptedPassword());
            byte[] bytes = gmailClient.downloadAttachment(gmail, pdf.getGmailMessageId(), pdf.getAttachmentId());
            result.put("pdfBytes", bytes.length);

            String text;
            try (PDDocument doc = PDDocument.load(bytes, password)) {
                text = new PDFTextStripper().getText(doc);
            }
            result.put("textLength", text.length());
            result.put("fullText", text);

            // Show which lines have B/S indicators
            List<String> tradeLines = new ArrayList<>();
            for (String line : text.split("\\n")) {
                String t = line.trim();
                if (t.length() >= 10) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s|\\d)(B|S|BUY|SELL)(?:\\s|\\d|$)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
                    if (m.find()) tradeLines.add(t);
                }
            }
            result.put("linesWithBuySell", tradeLines);

            // Try parsing
            List<ParsedEmail> cnResult = com.marketai.gmail.parser.ContractNoteHelper.parseContractNoteRows(text, pdf.getSubject(), "MStock");
            List<String> trades = new ArrayList<>();
            for (ParsedEmail pe : cnResult) {
                trades.add(pe.getType() + " " + pe.getSymbol() + " x" + pe.getQuantity() + " @ " + pe.getPrice() + " on " + pe.getTradeDate());
            }
            result.put("parsedTrades", trades);
            result.put("parsedCount", cnResult.size());

        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result;
    }

    public static class PdfUnlockResult {
        public final boolean unlocked;
        public final String message;
        public PdfUnlockResult(boolean unlocked, String message) { this.unlocked = unlocked; this.message = message; }
    }
}
