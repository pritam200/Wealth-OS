package com.marketai.reconciliation.controller;

import com.marketai.auth.entity.User;
import com.marketai.reconciliation.dto.ReconciliationReportDto;
import com.marketai.reconciliation.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final com.marketai.common.cache.PageCaches caches;
    private final com.marketai.reconciliation.service.ReconciliationCenterService centerService;
    private final com.marketai.reconciliation.service.ReconciliationIssueService issueService;
    private final com.marketai.reconciliation.service.DataAuditService dataAuditService;
    private final com.marketai.reconciliation.service.DataRebuildService dataRebuildService;
    private final com.marketai.reconciliation.service.BrokerStatementResetService statementResetService;

    /** Read-only: every stored record classified VERIFIED / DUPLICATE / CONFLICT / CORRUPTED / MISSING / REQUIRES_RECONCILIATION. */
    @GetMapping("/data-audit")
    public ResponseEntity<com.marketai.reconciliation.service.DataAuditService.Report> dataAudit(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(dataAuditService.audit(user.getId()));
    }

    @PostMapping("/backups")
    public ResponseEntity<com.marketai.reconciliation.service.DataRebuildService.BackupInfo> backup(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(dataRebuildService.createBackup(user.getId()));
    }

    @GetMapping("/backups")
    public ResponseEntity<java.util.List<com.marketai.reconciliation.service.DataRebuildService.BackupInfo>> backups(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(dataRebuildService.listBackups(user.getId()));
    }

    @GetMapping("/backups/{id}/download")
    public ResponseEntity<String> downloadBackup(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wealth-os-backup-" + id + ".json\"")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(dataRebuildService.backupPayload(user.getId(), id));
    }

    /** Needs a fresh backup id, confirm="REBUILD" and the exact records to remove. */
    @PostMapping("/rebuild")
    public ResponseEntity<com.marketai.reconciliation.service.DataRebuildService.RebuildResult> rebuild(
            @AuthenticationPrincipal User user,
            @RequestBody com.marketai.reconciliation.service.DataRebuildService.RebuildRequest req) {
        return ResponseEntity.ok(dataRebuildService.rebuild(user.getId(), req));
    }

    /** What resetting a broker portfolio's shares to its holdings statement would change. Nothing is written. */
    @PostMapping(value = "/statement-reset/preview", consumes = "multipart/form-data")
    public ResponseEntity<com.marketai.reconciliation.service.BrokerStatementResetService.Preview> statementResetPreview(
            @AuthenticationPrincipal User user,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam("portfolioId") Long portfolioId,
            @RequestParam("asOf") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate asOf) {
        return ResponseEntity.ok(statementResetService.preview(user.getId(), portfolioId, asOf, statementRows(file)));
    }

    /** Needs a fresh backup id and confirm="RESET"; the same file and date as the preview. */
    @PostMapping(value = "/statement-reset/apply", consumes = "multipart/form-data")
    public ResponseEntity<com.marketai.reconciliation.service.BrokerStatementResetService.Preview> statementResetApply(
            @AuthenticationPrincipal User user,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam("portfolioId") Long portfolioId,
            @RequestParam("asOf") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate asOf,
            @RequestParam("backupId") Long backupId,
            @RequestParam("confirm") String confirm) {
        return ResponseEntity.ok(statementResetService.apply(user.getId(), portfolioId, asOf, statementRows(file), backupId, confirm));
    }

    private static java.util.List<com.marketai.reconciliation.service.BrokerHoldingsStatement.Row> statementRows(
            org.springframework.web.multipart.MultipartFile file) {
        try {
            return com.marketai.reconciliation.service.BrokerHoldingsStatement.parse(file.getBytes());
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (java.io.IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "The file could not be read.");
        }
    }

    @GetMapping("/report")
    public ResponseEntity<ReconciliationReportDto> report(@AuthenticationPrincipal User user) {
        // Every check, about a hundred queries: the last run shows at once, and a newer one runs behind it.
        return ResponseEntity.ok(caches.<ReconciliationReportDto>of("reconciliation-report", java.time.Duration.ofMinutes(5), false)
            .get(user.getId(), () -> reconciliationService.checkAll(user.getId())));
    }

    /** The Reconciliation Center: ingestion totals, open issues, failed emails, background jobs. */
    @GetMapping("/center")
    public ResponseEntity<com.marketai.reconciliation.service.ReconciliationCenterService.Center> center(
            @AuthenticationPrincipal User user, @RequestParam(defaultValue = "true") boolean refresh) {
        return ResponseEntity.ok(centerService.center(user.getId(), refresh));
    }

    /** Marks an issue as seen and accepted for now; it stays listed until it goes away. */
    @PostMapping("/issues/{id}/acknowledge")
    public ResponseEntity<com.marketai.reconciliation.entity.ReconciliationIssueRecord> acknowledge(
            @AuthenticationPrincipal User user, @PathVariable Long id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        return ResponseEntity.ok(issueService.acknowledge(user.getId(), id, body != null ? body.get("note") : null));
    }

    /** The user says it isn't a problem; it stays out of the open list while the check keeps finding it. */
    @PostMapping("/issues/{id}/ignore")
    public ResponseEntity<com.marketai.reconciliation.entity.ReconciliationIssueRecord> ignore(
            @AuthenticationPrincipal User user, @PathVariable Long id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        return ResponseEntity.ok(issueService.ignore(user.getId(), id, body != null ? body.get("note") : null));
    }

    /** Back to the open list, from acknowledged or ignored. */
    @PostMapping("/issues/{id}/reopen")
    public ResponseEntity<com.marketai.reconciliation.entity.ReconciliationIssueRecord> reopen(
            @AuthenticationPrincipal User user, @PathVariable Long id) {
        return ResponseEntity.ok(issueService.reopen(user.getId(), id));
    }

    /** Every check the Center runs, and what each looks for. */
    @GetMapping("/checks")
    public ResponseEntity<java.util.List<com.marketai.reconciliation.check.ReconciliationRegistry.CheckInfo>> checks() {
        return ResponseEntity.ok(reconciliationService.describeChecks());
    }
}
