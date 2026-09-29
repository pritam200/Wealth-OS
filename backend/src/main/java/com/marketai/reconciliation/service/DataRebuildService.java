package com.marketai.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.reconciliation.entity.DataBackup;
import com.marketai.reconciliation.repository.DataBackupRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * The controlled rebuild over existing data. Three rules:
 *
 * <ol>
 *   <li><b>Backup first.</b> A rebuild needs a backup of the user's financial tables taken in the
 *       last {@value #BACKUP_VALID_HOURS} hours and not already used by an earlier rebuild.</li>
 *   <li><b>Explicit go-ahead.</b> The request must say {@code confirm: "REBUILD"} and list, by id,
 *       every record to remove. Nothing is removed that the user did not name.</li>
 *   <li><b>Only what the audit proves.</b> A named record is removed only if the audit, run again
 *       at that moment, classifies it DUPLICATE — a later copy of a record that stays. Anything
 *       else in the list fails the whole request; nothing is half-applied.</li>
 * </ol>
 *
 * <p>Holdings are then re-derived from their ledgers. That is derived data, so it needs no
 * selection — but it still runs only inside a confirmed, backed-up rebuild.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataRebuildService {

    static final int BACKUP_VALID_HOURS = 24;
    public static final String CONFIRMATION = "REBUILD";

    private final DataBackupRepository backupRepo;
    private final DataAuditService auditService;
    private final ExpenseRepository expenseRepo;
    private final IncomeRepository incomeRepo;
    private final TransactionRepository transactionRepo;
    private final PortfolioService portfolioService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** The user's rows, table by table. Every query is scoped to the user. */
    private static final LinkedHashMap<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("expenses", "SELECT * FROM expenses WHERE user_id = ? ORDER BY id");
        TABLES.put("incomes", "SELECT * FROM incomes WHERE user_id = ? ORDER BY id");
        TABLES.put("portfolios", "SELECT * FROM portfolios WHERE user_id = ? ORDER BY id");
        TABLES.put("holdings", "SELECT h.* FROM holdings h JOIN portfolios p ON h.portfolio_id = p.id WHERE p.user_id = ? ORDER BY h.id");
        TABLES.put("transactions", "SELECT t.* FROM transactions t JOIN holdings h ON t.holding_id = h.id "
            + "JOIN portfolios p ON h.portfolio_id = p.id WHERE p.user_id = ? ORDER BY t.id");
        TABLES.put("mf_redemptions", "SELECT * FROM mf_redemptions WHERE user_id = ? ORDER BY id");
        TABLES.put("fixed_deposits", "SELECT * FROM fixed_deposits WHERE user_id = ? ORDER BY id");
        TABLES.put("recurring_deposits", "SELECT * FROM recurring_deposits WHERE user_id = ? ORDER BY id");
        TABLES.put("card_statements", "SELECT * FROM card_statements WHERE user_id = ? ORDER BY id");
        TABLES.put("card_payments", "SELECT * FROM card_payments WHERE user_id = ? ORDER BY id");
        TABLES.put("imported_transaction_fingerprints", "SELECT * FROM imported_transaction_fingerprints WHERE user_id = ? ORDER BY id");
    }

    @Data @Builder
    public static class BackupInfo {
        private Long id;
        private LocalDateTime createdAt;
        private Map<String, Integer> rowCounts;
        private LocalDateTime appliedAt;
        private String appliedSummary;
        private boolean usable;
    }

    public record Removal(DataAuditService.Entity entity, Long id) {}

    @Data
    public static class RebuildRequest {
        private Long backupId;
        private String confirm;
        private List<Removal> remove = new ArrayList<>();
    }

    @Data @Builder
    public static class RebuildResult {
        private Long backupId;
        private int expensesRemoved;
        private int incomesRemoved;
        private int transactionsRemoved;
        private int refundsRelinked;
        private int holdingsRederived;
        private String summary;
    }

    @Transactional
    public BackupInfo createBackup(Long userId) {
        Map<String, List<Map<String, Object>>> payload = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, String> t : TABLES.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : jdbc.queryForList(t.getValue(), userId)) {
                Map<String, Object> clean = new LinkedHashMap<>();
                // Dates and timestamps as ISO text, so the file reads the same everywhere.
                row.forEach((k, v) -> clean.put(k, v instanceof java.util.Date || v instanceof java.time.temporal.Temporal ? v.toString() : v));
                rows.add(clean);
            }
            payload.put(t.getKey(), rows);
            counts.put(t.getKey(), rows.size());
        }
        try {
            DataBackup b = backupRepo.save(DataBackup.builder()
                .userId(userId)
                .payload(objectMapper.writeValueAsString(Map.of("userId", userId, "takenAt", LocalDateTime.now().toString(), "tables", payload)))
                .rowCounts(objectMapper.writeValueAsString(counts))
                .build());
            log.info("Backup {} taken for user {}: {}", b.getId(), userId, counts);
            return info(b);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The backup could not be written: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<BackupInfo> listBackups(Long userId) {
        return backupRepo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::info).toList();
    }

    @Transactional(readOnly = true)
    public String backupPayload(Long userId, Long backupId) {
        return backupRepo.findByIdAndUserId(backupId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Backup not found")).getPayload();
    }

    @Transactional
    public RebuildResult rebuild(Long userId, RebuildRequest req) {
        if (req == null || !CONFIRMATION.equals(req.getConfirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rebuild must be confirmed with \"" + CONFIRMATION + "\".");
        }
        if (req.getBackupId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Take a backup first and pass its id.");
        }
        DataBackup backup = backupRepo.findByIdAndUserId(req.getBackupId(), userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Backup not found"));
        if (!usable(backup)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, backup.getAppliedAt() != null
                ? "That backup was already used by a rebuild on " + backup.getAppliedAt() + ". Take a new one."
                : "That backup is more than " + BACKUP_VALID_HOURS + " hours old. Take a new one so it reflects the data being changed.");
        }

        // Re-audit now: the request can only name records that are duplicates at this moment.
        DataAuditService.Report report = auditService.audit(userId);
        Map<Removal, DataAuditService.Finding> duplicates = new HashMap<>();
        for (DataAuditService.Finding f : report.getFindings()) {
            if (f.getClassification() == DataAuditService.Classification.DUPLICATE) duplicates.put(new Removal(f.getEntity(), f.getId()), f);
        }
        List<Removal> requested = req.getRemove() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(req.getRemove()));
        List<String> refused = new ArrayList<>();
        for (Removal r : requested) {
            DataAuditService.Finding f = duplicates.get(r);
            if (f == null) refused.add(r.entity() + " " + r.id() + " is not a duplicate");
            else if (r.entity() == DataAuditService.Entity.TRANSACTION) {
                Transaction t = transactionRepo.findById(r.id()).orElse(null);
                if (t == null || t.getType() != Transaction.TransactionType.BUY) {
                    // A duplicated sale also wrote a redemption/tax record; removing only the
                    // ledger row would leave that behind, so it is left for a person.
                    refused.add("TRANSACTION " + r.id() + " is a sale — remove it by hand with its redemption record");
                }
            } else if (r.entity() != DataAuditService.Entity.EXPENSE && r.entity() != DataAuditService.Entity.INCOME) {
                refused.add(r.entity() + " " + r.id() + " can't be removed by a rebuild");
            }
        }
        if (!refused.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing was changed: " + String.join("; ", refused) + ".");
        }

        int expenses = 0, incomes = 0, txns = 0, relinked = 0;
        for (Removal r : requested) {
            Long keep = duplicates.get(r).getDuplicateOf();
            switch (r.entity()) {
                case EXPENSE -> {
                    // A refund linked to the copy now belongs to the purchase that stays.
                    for (Expense refund : expenseRepo.findByRefundOfExpenseId(r.id())) {
                        refund.setRefundOfExpenseId(keep);
                        expenseRepo.save(refund);
                        relinked++;
                    }
                    expenseRepo.deleteById(r.id());
                    expenses++;
                }
                case INCOME -> { incomeRepo.deleteById(r.id()); incomes++; }
                case TRANSACTION -> { transactionRepo.deleteById(r.id()); txns++; }
                default -> { }
            }
        }
        transactionRepo.flush();
        int rederived = portfolioService.rebuildHoldingsFromTransactions(userId);

        String summary = String.format("Removed %d duplicate expense(s), %d income(s), %d purchase(s); re-linked %d refund(s); "
            + "re-derived %d holding(s) from their ledgers.", expenses, incomes, txns, relinked, rederived);
        backup.setAppliedAt(LocalDateTime.now());
        backup.setAppliedSummary(summary);
        backupRepo.save(backup);
        log.info("Rebuild for user {} under backup {}: {}", userId, backup.getId(), summary);
        return RebuildResult.builder().backupId(backup.getId()).expensesRemoved(expenses).incomesRemoved(incomes)
            .transactionsRemoved(txns).refundsRelinked(relinked).holdingsRederived(rederived).summary(summary).build();
    }

    static boolean usable(DataBackup b) {
        return b.getAppliedAt() == null && b.getCreatedAt() != null
            && b.getCreatedAt().isAfter(LocalDateTime.now().minusHours(BACKUP_VALID_HOURS));
    }

    private BackupInfo info(DataBackup b) {
        Map<String, Integer> counts = Map.of();
        try {
            if (b.getRowCounts() != null) {
                counts = objectMapper.readValue(b.getRowCounts(), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Integer>>() {});
            }
        } catch (Exception ignored) { }
        return BackupInfo.builder().id(b.getId()).createdAt(b.getCreatedAt()).rowCounts(counts)
            .appliedAt(b.getAppliedAt()).appliedSummary(b.getAppliedSummary()).usable(usable(b)).build();
    }
}
