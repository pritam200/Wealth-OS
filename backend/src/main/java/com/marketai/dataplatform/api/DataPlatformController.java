package com.marketai.dataplatform.api;

import com.marketai.auth.entity.User;
import com.marketai.dataplatform.domain.*;
import com.marketai.dataplatform.service.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** The API behind Data Connections, Data Health, the Reconciliation Center and transaction detail. */
@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataPlatformController {

    private final DataConnectionService connectionService;
    private final ConsentService consentService;
    private final FinancialDataSyncService syncService;
    private final DataQualityService quality;
    private final IssueService issues;
    private final ResolutionService resolution;
    private final TransactionQueryService transactions;
    private final CsvImportService csvImport;
    private final LegacyBackfillService backfill;
    private final FamilyAccessService family;

    /* ───────────── connections ───────────── */

    @GetMapping("/connections")
    public DataConnectionService.Overview connections(@AuthenticationPrincipal User user) {
        return connectionService.overview(user.getId());
    }

    @Data public static class ConsentBody { private String providerId; private String institution; private String mobile; }

    @PostMapping("/connections")
    public ConsentService.Started startConsent(@AuthenticationPrincipal User user, @RequestBody ConsentBody body) {
        return consentService.start(user.getId(), body.getProviderId(), body.getInstitution(), body.getMobile());
    }

    @PostMapping("/consents/{id}/refresh")
    public ConsentRecord refreshConsent(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return consentService.refresh(user.getId(), id);
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal User user, @PathVariable Long id) {
        consentService.revoke(user.getId(), id);
        return ResponseEntity.noContent().build();
    }

    @Data public static class SyncBody { private SyncKind kind = SyncKind.INCREMENTAL; }

    @PostMapping("/connections/{id}/sync")
    public FinancialSyncRun sync(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody(required = false) SyncBody body) {
        SyncKind kind = body == null || body.getKind() == null ? SyncKind.INCREMENTAL : body.getKind();
        return switch (kind) {
            case INITIAL -> syncService.initialSync(user.getId(), id);
            case INCREMENTAL -> syncService.incrementalSync(user.getId(), id);
            case FULL_RECONCILIATION -> syncService.fullReconciliation(user.getId(), id);
            case RETRY_FAILED -> syncService.retryFailedSync(user.getId(), id);
        };
    }

    @GetMapping("/sync-runs")
    public List<FinancialSyncRun> syncRuns(@AuthenticationPrincipal User user) { return syncService.recent(user.getId()); }

    /* ───────────── health & issues ───────────── */

    @GetMapping("/health")
    public DataQualityService.Health health(@AuthenticationPrincipal User user, @RequestParam(defaultValue = "self") String scope) {
        return quality.health(user.getId(), "family".equalsIgnoreCase(scope));
    }

    @GetMapping("/issues")
    public List<LedgerIssue> issues(@AuthenticationPrincipal User user, @RequestParam(defaultValue = "open") String status) {
        return "all".equalsIgnoreCase(status)
            ? issues.list(user.getId(), EnumSet.allOf(IssueStatus.class))
            : issues.open(user.getId());
    }

    @Data public static class ActionBody { private ResolutionAction action; private String note; private Long mergeTargetId; }

    @PostMapping("/issues/{id}/action")
    public LedgerIssue act(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody ActionBody body) {
        if (body.getAction() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required");
        return resolution.act(user.getId(), id, body.getAction(), body.getNote(), body.getMergeTargetId());
    }

    /* ───────────── transactions ───────────── */

    /** Books one ledger transaction into the portfolio / income / deposit tracker, on the user's explicit request. */
    @PostMapping("/transactions/{id}/add-to-portfolio")
    public LegacyApplyService.Result addToPortfolio(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return resolution.addToPortfolio(user.getId(), id);
    }

    @GetMapping("/transactions")
    public List<TransactionQueryService.Row> transactions(@AuthenticationPrincipal User user,
                                                         @RequestParam(required = false) Long assetId,
                                                         @RequestParam(required = false) Long accountId,
                                                         @RequestParam(defaultValue = "self") String scope,
                                                         @RequestParam(defaultValue = "200") int limit) {
        return transactions.list(user.getId(), "family".equalsIgnoreCase(scope), assetId, accountId, limit);
    }

    @GetMapping("/transactions/{id}")
    public TransactionQueryService.Detail transaction(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return transactions.detail(user.getId(), id);
    }

    /* ───────────── imports & migration ───────────── */

    @Data public static class CsvBody {
        private String csv; private String institution; private String accountId;
        private SourceType sourceType = SourceType.STATEMENT; private AssetClass assetClass = AssetClass.MUTUAL_FUND;
        private String provider; private boolean completeStatement;
    }

    @PostMapping("/import/csv")
    public CsvImportService.Summary importCsv(@AuthenticationPrincipal User user, @RequestBody CsvBody body) {
        if (body.getCsv() == null || body.getCsv().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "csv is required");
        if (body.getCsv().length() > 2_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large");
        return csvImport.importCsv(user.getId(), body.getCsv(), new CsvImportService.Options(body.getInstitution(), body.getAccountId(),
            body.getSourceType(), body.getAssetClass(), body.getProvider(), body.isCompleteStatement()));
    }

    /** A statement file: CSV or Excel (.xlsx/.xls). Excel's first sheet is converted to CSV and imported identically. */
    @PostMapping(value = "/import/file", consumes = "multipart/form-data")
    public CsvImportService.Summary importFile(@AuthenticationPrincipal User user,
                                               @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                               @RequestParam String institution,
                                               @RequestParam(required = false) String accountId,
                                               @RequestParam(defaultValue = "STATEMENT") SourceType sourceType,
                                               @RequestParam(defaultValue = "MUTUAL_FUND") AssetClass assetClass,
                                               @RequestParam(defaultValue = "false") boolean completeStatement) throws java.io.IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");
        if (file.getSize() > 5_000_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String csv;
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) csv = ExcelCsvConverter.toCsv(file.getBytes());
        else if (name.endsWith(".csv") || name.endsWith(".txt")) csv = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a .csv, .xlsx or .xls file");
        return csvImport.importCsv(user.getId(), csv, new CsvImportService.Options(institution, accountId, sourceType, assetClass, "file-import", completeStatement));
    }

    @PostMapping("/backfill")
    public LegacyBackfillService.Summary backfill(@AuthenticationPrincipal User user) { return backfill.backfill(user.getId()); }

    /* ───────────── family ───────────── */

    @GetMapping("/families")
    public List<Map<String, Object>> families(@AuthenticationPrincipal User user) { return family.myFamilies(user.getId()); }

    @Data public static class FamilyBody { private String name; private String email; private String role; private Boolean shares; }

    @PostMapping("/families")
    public Family createFamily(@AuthenticationPrincipal User user, @RequestBody FamilyBody b) { return family.create(user.getId(), b.getName()); }

    @PostMapping("/families/{id}/members")
    public FamilyMember addMember(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody FamilyBody b) {
        return family.addMember(user.getId(), id, b.getEmail(), b.getRole());
    }

    @PutMapping("/families/{id}/sharing")
    public FamilyMember sharing(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody FamilyBody b) {
        return family.setSharing(user.getId(), id, Boolean.TRUE.equals(b.getShares()));
    }

    @DeleteMapping("/families/{id}/members/{memberUserId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal User user, @PathVariable Long id, @PathVariable Long memberUserId) {
        family.removeMember(user.getId(), id, memberUserId);
        return ResponseEntity.noContent().build();
    }

    @Data public static class OwnershipBody { private Ownership ownership; private Long familyId; private List<Long> coOwnerUserIds; private List<String> coOwnerEmails; }

    @PutMapping("/accounts/{id}/ownership")
    public FinancialAccount ownership(@AuthenticationPrincipal User user, @PathVariable Long id, @RequestBody OwnershipBody b) {
        if (b.getCoOwnerEmails() != null && !b.getCoOwnerEmails().isEmpty())
            return family.setOwnershipByEmail(user.getId(), id, b.getOwnership(), b.getFamilyId(), b.getCoOwnerEmails());
        return family.setOwnership(user.getId(), id, b.getOwnership(), b.getFamilyId(), b.getCoOwnerUserIds());
    }
}
