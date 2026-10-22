package com.marketai.identity.controller;

import com.marketai.auth.entity.User;
import com.marketai.gmail.service.PdfImportService;
import com.marketai.identity.dto.FinancialIdentityRequest;
import com.marketai.identity.dto.FinancialIdentityStatus;
import com.marketai.identity.service.FinancialIdentityService;
import com.marketai.sync.service.SyncLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Financial identity (PAN / date of birth) used to derive statement passwords.
 *
 * <p>Note what is absent: there is no endpoint that returns a stored PAN or date of birth, by
 * design. {@link #status} reports only whether each value is present. A "reveal" endpoint would
 * put a government identifier into a response body, a browser cache, and a proxy log, to no
 * benefit — the user already knows their own PAN, and the only consumer that needs the value is
 * the password engine running server-side.
 */
@RestController
@RequestMapping("/api/identity")
@RequiredArgsConstructor
@Slf4j
public class FinancialIdentityController {

    private final FinancialIdentityService service;
    private final PdfImportService pdfImportService;
    private final SyncLockService syncLockService;

    @GetMapping
    public ResponseEntity<FinancialIdentityStatus> status(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(FinancialIdentityStatus.of(
            service.hasPan(user.getId()), service.hasDob(user.getId())));
    }

    @PutMapping
    public ResponseEntity<FinancialIdentityStatus> save(@AuthenticationPrincipal User user,
                                                        @RequestBody FinancialIdentityRequest req) {
        service.save(user.getId(), req.pan(), req.dateOfBirth());
        retryLockedStatements(user.getId());
        return ResponseEntity.ok(FinancialIdentityStatus.of(
            service.hasPan(user.getId()), service.hasDob(user.getId())));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal User user) {
        service.clear(user.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * A new PAN or date of birth can open statements that were waiting on it. Without this they
     * stay locked until a sync finishes, and a long backlog sync may not finish for hours.
     * Runs off the request thread, under the user's sync lock so it never races a running sync
     * (which retries locked statements itself when it ends).
     */
    private void retryLockedStatements(Long userId) {
        Thread t = new Thread(() -> {
            var lock = syncLockService.tryAcquire(userId);
            if (lock.isEmpty()) {
                log.info("Sync running for user {} — it will retry locked statements when it finishes", userId);
                return;
            }
            try (var held = lock.get()) {
                int opened = pdfImportService.bulkAutoUnlock(userId);
                log.info("Identity saved for user {}: retried locked statements, {} opened", userId, opened);
            } catch (Exception e) {
                log.warn("Retrying locked statements for user {} failed: {}", userId, e.getMessage());
            }
        }, "identity-unlock-" + userId);
        t.setDaemon(true);
        t.start();
    }
}
