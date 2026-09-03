package com.marketai.gmail.controller;

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.marketai.auth.entity.User;
import com.marketai.gmail.dto.GmailStatusResponse;
import com.marketai.gmail.dto.GmailSyncResult;
import com.marketai.gmail.dto.ReconciliationReportDto;
import com.marketai.gmail.entity.ExcludedSender;
import com.marketai.gmail.entity.GmailToken;
import com.marketai.gmail.dto.SavedPasswordResponse;
import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.entity.ProcessedEmail;
import com.marketai.gmail.repository.ExcludedSenderRepository;
import com.marketai.gmail.repository.GmailTokenRepository;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import com.marketai.gmail.service.GmailClientService;
import com.marketai.gmail.service.GmailSyncService;
import com.marketai.gmail.service.PdfImportService;
import com.marketai.gmail.service.ReconciliationReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/gmail")
@RequiredArgsConstructor
public class GmailController {

    private final GmailClientService gmailClientService;
    private final GmailSyncService gmailSyncService;
    private final GmailTokenRepository tokenRepo;
    private final ProcessedEmailRepository processedRepo;
    private final PdfImportService pdfImportService;
    private final ExcludedSenderRepository excludedSenderRepo;
    private final ReconciliationReportService reconciliationReportService;

    @Value("${gmail.frontend-url:http://localhost:5174}")
    private String frontendUrl;

    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl(@AuthenticationPrincipal User user) {
        if (!gmailClientService.isConfigured()) {
            Map<String, String> err = new HashMap<>();
            err.put("error", "Gmail credentials not configured. Set GMAIL_CLIENT_ID and GMAIL_CLIENT_SECRET environment variables.");
            return ResponseEntity.badRequest().body(err);
        }
        try {
            String url = gmailClientService.getAuthorizationUrl(user.getId());
            Map<String, String> resp = new HashMap<>();
            resp.put("url", url);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, String> err = new HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    @GetMapping("/callback")
    public void callback(@RequestParam String code,
                         @RequestParam(required = false) String state,
                         HttpServletResponse response) throws IOException {
        try {
            Long userId = gmailClientService.verifyAndExtractUserId(state);
            if (userId == null) { response.sendRedirect(frontendUrl + "/#gmail-error"); return; }

            User user = new User();
            user.setId(userId);

            GoogleTokenResponse tokens = gmailClientService.exchangeCode(code);

            // Build Gmail service to get connected email
            String email = null;
            try {
                com.google.api.services.gmail.Gmail gmail = gmailClientService.buildGmailService(tokens.getAccessToken(), tokens.getRefreshToken());
                email = gmailClientService.getUserEmail(gmail);
            } catch (Exception ignored) {}

            GmailToken existing = tokenRepo.findByUserId(userId).orElse(null);
            if (existing != null) {
                existing.setAccessToken(tokens.getAccessToken());
                existing.setRefreshToken(tokens.getRefreshToken() != null ? tokens.getRefreshToken() : existing.getRefreshToken());
                existing.setExpiresAt(LocalDateTime.now().plusSeconds(tokens.getExpiresInSeconds() != null ? tokens.getExpiresInSeconds() : 3600));
                if (email != null) existing.setConnectedEmail(email);
                tokenRepo.save(existing);
            } else {
                GmailToken token = GmailToken.builder()
                        .user(user)
                        .accessToken(tokens.getAccessToken())
                        .refreshToken(tokens.getRefreshToken())
                        .expiresAt(LocalDateTime.now().plusSeconds(tokens.getExpiresInSeconds() != null ? tokens.getExpiresInSeconds() : 3600))
                        .connectedEmail(email)
                        .build();
                tokenRepo.save(token);
            }

            response.sendRedirect(frontendUrl + "/#gmail-connected");
        } catch (Exception e) {
            response.sendRedirect(frontendUrl + "/#gmail-error?msg=" + e.getMessage());
        }
    }

    @GetMapping("/status")
    public ResponseEntity<GmailStatusResponse> status(@AuthenticationPrincipal User user) {
        GmailToken token = tokenRepo.findByUserId(user.getId()).orElse(null);
        if (token == null) {
            return ResponseEntity.ok(GmailStatusResponse.builder().connected(false).build());
        }
        long imported = token.getImportedCount() != null ? token.getImportedCount() : 0;
        return ResponseEntity.ok(GmailStatusResponse.builder()
                .connected(true)
                .connectedEmail(token.getConnectedEmail())
                .lastSyncAt(token.getLastSyncAt())
                .importedCount(imported)
                .build());
    }

    @PostMapping("/sync")
    public ResponseEntity<GmailSyncResult> triggerSync(@AuthenticationPrincipal User user) {
        if (!gmailClientService.isConfigured()) {
            return ResponseEntity.ok(GmailSyncResult.builder().imported(0).skipped(0).failed(0)
                    .summaries(Collections.singletonList("Gmail not configured"))
                    .logEntries(Collections.emptyList()).build());
        }
        GmailSyncResult result = gmailSyncService.syncForUser(user.getId());
        return ResponseEntity.ok(result);
    }

    // Lets a user self-diagnose sync issues: which parser matched each email (if any),
    // whether it imported/skipped/failed, and why — instead of transactions just silently
    // not appearing with no way to tell whether Gmail returned the email at all.
    @GetMapping("/history")
    public ResponseEntity<List<ProcessedEmail>> history(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(processedRepo.findByUserIdOrderByProcessedAtDesc(user.getId(), PageRequest.of(0, Math.min(limit, 500))));
    }

    // "Locked statements" — financial emails whose PDF attachment couldn't be read
    // automatically and where no saved password (see /saved-passwords) unlocked it yet.
    // On a successful manual unlock the password is encrypted and saved per sender domain,
    // so future statements from the same institution unlock automatically.
    @GetMapping("/pending-pdfs")
    public ResponseEntity<List<PendingPdf>> pendingPdfs(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pdfImportService.list(user.getId()));
    }

    @PostMapping("/pending-pdfs/{id}/unlock")
    public ResponseEntity<Map<String, Object>> unlockPdf(
            @AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody Map<String, String> body) {
        PdfImportService.PdfUnlockResult result = pdfImportService.unlock(user.getId(), id, body.get("password"));
        Map<String, Object> resp = new HashMap<>();
        resp.put("unlocked", result.unlocked);
        resp.put("message", result.message);
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/pending-pdfs/{id}")
    public ResponseEntity<Void> dismissPdf(@AuthenticationPrincipal User user, @PathVariable Long id) {
        pdfImportService.dismiss(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    // Persistent, queryable reconciliation report — unlike GmailSyncResult.ReconciliationReport
    // (a one-shot snapshot only returned from the sync endpoints), this reads the full
    // ProcessedEmail + PendingPdf history for the user so it survives across page loads and
    // syncs, and every Imported/Failed/Unparsed row is traceable back to its source email or
    // attachment. See ReconciliationReportService for exactly how each bucket is computed and
    // why "Updated", "Duplicate" and "Reconciled" are reported as not-determinable rather than
    // guessed.
    @GetMapping("/reconciliation-report")
    public ResponseEntity<ReconciliationReportDto> reconciliationReport(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(reconciliationReportService.build(user.getId()));
    }

    @GetMapping("/contract-note-debug")
    public ResponseEntity<List<PendingPdf>> contractNoteDebug(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pdfImportService.listAll(user.getId()));
    }

    @PostMapping("/reprocess-failed")
    public ResponseEntity<Map<String, Object>> reprocessFailed(@AuthenticationPrincipal User user) {
        pdfImportService.resetFailedPdfs(user.getId());
        int unlocked = pdfImportService.bulkAutoUnlock(user.getId());
        List<PendingPdf> all = pdfImportService.listAll(user.getId());
        Map<String, Object> result = new HashMap<>();
        result.put("totalPdfs", all.size());
        result.put("autoUnlocked", unlocked);
        result.put("pdfs", all);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/debug-pdf/{id}")
    public ResponseEntity<Map<String, Object>> debugPdf(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return ResponseEntity.ok(pdfImportService.debugPdf(user.getId(), id));
    }

    // Lets a user exclude emails that belong to someone else sharing the same inbox/broker
    // (e.g. a parent's account) from ever being auto-imported. Pattern is matched against
    // sender + subject + body, since one broker address often serves multiple accounts.
    @GetMapping("/excluded-senders")
    public ResponseEntity<List<ExcludedSender>> listExcludedSenders(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(excludedSenderRepo.findByUserIdOrderByCreatedAtDesc(user.getId()));
    }

    @PostMapping("/excluded-senders")
    public ResponseEntity<ExcludedSender> addExcludedSender(
            @AuthenticationPrincipal User user, @RequestBody Map<String, String> body) {
        String pattern = body.get("pattern");
        if (pattern == null || pattern.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        ExcludedSender saved = excludedSenderRepo.save(ExcludedSender.builder()
                .userId(user.getId())
                .pattern(pattern.trim())
                .label(body.get("label"))
                .build());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/excluded-senders/{id}")
    public ResponseEntity<Void> removeExcludedSender(@AuthenticationPrincipal User user, @PathVariable Long id) {
        if (!excludedSenderRepo.existsByIdAndUserId(id, user.getId())) {
            return ResponseEntity.notFound().build();
        }
        excludedSenderRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // "Manage Saved Passwords" — statement-unlock passwords saved (encrypted) after a
    // successful manual unlock, reused automatically for future statements from the same
    // sender domain. The password itself is never returned here.
    @GetMapping("/saved-passwords")
    public ResponseEntity<List<SavedPasswordResponse>> listSavedPasswords(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pdfImportService.listSavedPasswords(user.getId()).stream()
                .map(SavedPasswordResponse::from).collect(Collectors.toList()));
    }

    @PutMapping("/saved-passwords/{id}")
    public ResponseEntity<Void> updateSavedPassword(
            @AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (password == null || password.trim().isEmpty()) return ResponseEntity.badRequest().build();
        pdfImportService.updateSavedPassword(user.getId(), id, password);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/saved-passwords/{id}")
    public ResponseEntity<Void> deleteSavedPassword(@AuthenticationPrincipal User user, @PathVariable Long id) {
        pdfImportService.deleteSavedPassword(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resync")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<GmailSyncResult> fullResync(@AuthenticationPrincipal User user) {
        if (!gmailClientService.isConfigured()) {
            return ResponseEntity.ok(GmailSyncResult.builder().imported(0).skipped(0).failed(0)
                    .summaries(Collections.singletonList("Gmail not configured"))
                    .logEntries(Collections.emptyList()).build());
        }
        // Full rebuild: delete ALL ProcessedEmail entries so every email is re-processed.
        // Content-based dedup in ParsedEmailImporter prevents actual domain-data duplicates
        // (same amount+date+merchant for expenses/income, same symbol+date+qty+price for trades).
        // This means: if the user deleted a dividend and runs Full Resync, it will be re-imported
        // from Gmail because the dedup check won't find a matching domain record.
        processedRepo.deleteByUserId(user.getId());

        // Reset importedCount for a clean baseline
        tokenRepo.findByUserId(user.getId()).ifPresent(token -> {
            token.setImportedCount(0);
            tokenRepo.save(token);
        });

        // Reset all PASSWORD_FAILED and FAILED PDFs back to NEEDS_PASSWORD so they get retried
        pdfImportService.resetFailedPasswords(user.getId());
        pdfImportService.resetFailedPdfs(user.getId());

        GmailSyncResult result = gmailSyncService.syncForUser(user.getId(), "365d");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/retry-failed")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<GmailSyncResult> retryFailed(@AuthenticationPrincipal User user) {
        if (!gmailClientService.isConfigured()) {
            return ResponseEntity.ok(GmailSyncResult.builder().imported(0).skipped(0).failed(0)
                    .summaries(Collections.singletonList("Gmail not configured"))
                    .logEntries(Collections.emptyList()).build());
        }
        processedRepo.deleteByUserIdAndStatusIn(user.getId(), java.util.Arrays.asList("SKIPPED", "FAILED"));
        pdfImportService.resetFailedPasswords(user.getId());
        pdfImportService.resetFailedPdfs(user.getId());
        GmailSyncResult result = gmailSyncService.syncForUser(user.getId(), "30d");
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/disconnect")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal User user) {
        tokenRepo.findByUserId(user.getId()).ifPresent(tokenRepo::delete);
        return ResponseEntity.noContent().build();
    }
}
