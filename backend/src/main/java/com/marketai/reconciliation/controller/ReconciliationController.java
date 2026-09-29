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
    private final com.marketai.reconciliation.service.ReconciliationCenterService centerService;
    private final com.marketai.reconciliation.service.ReconciliationIssueService issueService;
    private final com.marketai.reconciliation.service.DataAuditService dataAuditService;
    private final com.marketai.reconciliation.service.DataRebuildService dataRebuildService;

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

    @GetMapping("/report")
    public ResponseEntity<ReconciliationReportDto> report(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(reconciliationService.checkAll(user.getId()));
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
}
