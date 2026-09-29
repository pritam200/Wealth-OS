package com.marketai.reconciliation.service;

import com.marketai.expense.entity.Expense;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.gmail.entity.ImportedTransactionFingerprint;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.income.entity.Income;
import com.marketai.income.entity.IncomeSource;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.tax.lot.FifoLedger;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.entity.RecurringDeposit;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Classifies every stored financial record — expenses, incomes, ledger trades, holdings and
 * deposits — by how far it can be trusted. <b>Read-only</b>: nothing here writes, and nothing is
 * repaired; the report is what a rebuild ({@link DataRebuildService}) is allowed to act on.
 *
 * <ul>
 *   <li><b>VERIFIED</b> — consistent, and traceable to its source (an email or a hand entry).</li>
 *   <li><b>DUPLICATE</b> — a second copy of a record that is already there. Only the later copy
 *       is marked; the one it duplicates is named, so removing the copy loses nothing.</li>
 *   <li><b>CONFLICT</b> — a later document quoted the same payment reference with different
 *       details; which is right is for the user to decide.</li>
 *   <li><b>CORRUPTED</b> — the record contradicts itself (no amount, a future date, a price of
 *       nil) or a derived figure no longer matches the rows it is derived from.</li>
 *   <li><b>MISSING</b> — the record implies another that isn't there (a sale with no purchase,
 *       a closed deposit with no payout).</li>
 *   <li><b>REQUIRES_RECONCILIATION</b> — possibly right, but something needs a person to look:
 *       a likely duplicate from another source, a lapsed deposit, proceeds with no cost basis.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DataAuditService {

    public enum Classification { VERIFIED, DUPLICATE, CONFLICT, CORRUPTED, MISSING, REQUIRES_RECONCILIATION }

    public enum Entity { EXPENSE, INCOME, TRANSACTION, HOLDING, FIXED_DEPOSIT, RECURRING_DEPOSIT }

    /** A deposit this long past its maturity with nothing recorded has an outcome nobody booked. */
    static final int LAPSED_DEPOSIT_DAYS = 30;

    private final ExpenseRepository expenseRepo;
    private final IncomeRepository incomeRepo;
    private final PortfolioRepository portfolioRepo;
    private final HoldingRepository holdingRepo;
    private final TransactionRepository transactionRepo;
    private final FixedDepositRepository fdRepo;
    private final RecurringDepositRepository rdRepo;
    private final ImportedTransactionFingerprintRepository fingerprintRepo;
    private final com.marketai.gmail.repository.ProcessedEmailRepository processedEmailRepo;

    @Data @Builder
    public static class Finding {
        private Entity entity;
        private Long id;
        private Classification classification;
        private String reason;
        /** For a DUPLICATE, the record it duplicates (the one kept). */
        private Long duplicateOf;
        private BigDecimal amount;
        private LocalDate date;
        private String label;
    }

    @Data @Builder
    public static class Report {
        private LocalDateTime generatedAt;
        /** entity → classification → number of records. Every record is counted exactly once. */
        private Map<Entity, Map<Classification, Integer>> counts;
        /** How much the duplicates overstate spending and income. */
        private BigDecimal duplicateExpenseAmount;
        private BigDecimal duplicateIncomeAmount;
        /** Everything not VERIFIED, most serious first. */
        private List<Finding> findings;
        private int totalRecords;
        private int verifiedRecords;
    }

    private static final List<Classification> SEVERITY = List.of(
        Classification.CORRUPTED, Classification.CONFLICT, Classification.DUPLICATE,
        Classification.MISSING, Classification.REQUIRES_RECONCILIATION, Classification.VERIFIED);

    @Transactional(readOnly = true)
    public Report audit(Long userId) {
        LocalDate today = LocalDate.now();
        Set<String> conflicted = new HashSet<>();
        for (ImportedTransactionFingerprint f : fingerprintRepo.findByUserIdAndConflictDetectedTrue(userId)) {
            if (f.getFingerprint() != null) conflicted.add(f.getFingerprint());
        }
        Set<String> needsReview = new HashSet<>();
        for (ImportedTransactionFingerprint f : fingerprintRepo.findByUserIdAndDuplicateStateOrderByImportedAtAsc(userId, "NEEDS_REVIEW")) {
            if (f.getFingerprint() != null) needsReview.add(f.getFingerprint());
        }

        List<Expense> expenses = expenseRepo.findByUserIdOrderByExpenseDateAsc(userId);
        List<Income> incomes = incomeRepo.findByUserIdOrderByIncomeDateDesc(userId);
        EmailLines lines = emailLines(userId, expenses, incomes);

        List<Finding> all = new ArrayList<>();
        all.addAll(auditExpenses(expenses, conflicted, needsReview, lines, today));
        all.addAll(auditIncomes(incomes, conflicted, needsReview, lines, today));
        for (Portfolio p : portfolioRepo.findByUserIdOrderByIdAsc(userId)) {
            for (Holding h : holdingRepo.findByPortfolioId(p.getId())) {
                all.addAll(auditHolding(h, transactionRepo.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId()), conflicted, today));
            }
        }
        all.addAll(auditFds(fdRepo.findByUserIdOrderByCreatedAtDesc(userId), today));
        all.addAll(auditRds(rdRepo.findByUserIdOrderByCreatedAtDesc(userId), today));
        return summarise(all);
    }

    /**
     * How many cash lines each email produced, against how many it was read to contain. A
     * statement lists the same merchant and amount on different days as separate lines; the only
     * thing that tells those apart from one line booked twice is the email having fewer lines
     * than rows.
     */
    record EmailLines(Map<String, Integer> rows, Map<String, Integer> extracted) {
        static final EmailLines UNKNOWN = new EmailLines(Map.of(), Map.of());

        /** TRUE: more rows than lines (a re-booking). FALSE: every row is a line. null: can't tell. */
        Boolean overBooked(String emailId) {
            Integer n = extracted.get(emailId);
            if (n == null) return null;
            return rows.getOrDefault(emailId, 0) > n;
        }
    }

    private EmailLines emailLines(Long userId, List<Expense> expenses, List<Income> incomes) {
        Map<String, Integer> rows = new HashMap<>();
        expenses.forEach(e -> { if (e.getSourceEmailId() != null) rows.merge(e.getSourceEmailId(), 1, Integer::sum); });
        incomes.forEach(i -> { if (i.getSourceEmailId() != null) rows.merge(i.getSourceEmailId(), 1, Integer::sum); });
        Map<String, Integer> extracted = new HashMap<>();
        if (!rows.isEmpty()) {
            for (var m : processedEmailRepo.findByUserIdAndGmailMessageIdIn(userId, rows.keySet())) {
                if (m.getCounts() != null && m.getCounts().getExtracted() != null) extracted.put(m.getGmailMessageId(), m.getCounts().getExtracted());
            }
        }
        return new EmailLines(rows, extracted);
    }

    static Report summarise(List<Finding> all) {
        Map<Entity, Map<Classification, Integer>> counts = new EnumMap<>(Entity.class);
        for (Entity e : Entity.values()) {
            Map<Classification, Integer> m = new EnumMap<>(Classification.class);
            for (Classification c : Classification.values()) m.put(c, 0);
            counts.put(e, m);
        }
        BigDecimal dupExp = BigDecimal.ZERO, dupInc = BigDecimal.ZERO;
        for (Finding f : all) {
            counts.get(f.getEntity()).merge(f.getClassification(), 1, Integer::sum);
            if (f.getClassification() == Classification.DUPLICATE && f.getAmount() != null) {
                if (f.getEntity() == Entity.EXPENSE) dupExp = dupExp.add(f.getAmount());
                if (f.getEntity() == Entity.INCOME) dupInc = dupInc.add(f.getAmount());
            }
        }
        List<Finding> issues = all.stream()
            .filter(f -> f.getClassification() != Classification.VERIFIED)
            .sorted(Comparator.comparingInt((Finding f) -> SEVERITY.indexOf(f.getClassification()))
                .thenComparing(f -> f.getEntity().name()).thenComparing(f -> f.getId() == null ? 0 : f.getId()))
            .toList();
        return Report.builder()
            .generatedAt(LocalDateTime.now())
            .counts(counts)
            .duplicateExpenseAmount(dupExp)
            .duplicateIncomeAmount(dupInc)
            .findings(issues)
            .totalRecords(all.size())
            .verifiedRecords(all.size() - issues.size())
            .build();
    }

    // ── Expenses ──

    static List<Finding> auditExpenses(List<Expense> rows, Set<String> conflicted, Set<String> needsReview, EmailLines lines, LocalDate today) {
        List<Finding> out = new ArrayList<>();
        Map<Long, Expense> byId = new HashMap<>();
        for (Expense e : rows) byId.put(e.getId(), e);
        // The cross-day re-import bug: one email, one amount, one payee, booked again on a later
        // day — but only when the email has more rows than it had lines (see EmailLines). Rows
        // from one email on the SAME day are separate statement lines, not copies.
        Map<String, Expense> firstFromEmail = new HashMap<>();
        // Same amount, day and payee from two different sources: possibly the same payment.
        Map<String, Expense> firstOnDay = new HashMap<>();
        List<Expense> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(Expense::getExpenseDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Expense::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        for (Expense e : ordered) {
            Finding.FindingBuilder f = Finding.builder().entity(Entity.EXPENSE).id(e.getId()).amount(e.getAmount())
                .date(e.getExpenseDate()).label(e.getMerchant() != null ? e.getMerchant() : e.getDescription());
            String party = com.marketai.common.ledger.PartyNames.normalise(e.getMerchant() != null ? e.getMerchant() : e.getDescription());
            if (e.getAmount() == null || e.getAmount().signum() == 0) {
                out.add(f.classification(Classification.CORRUPTED).reason("No amount.").build());
                continue;
            }
            if (e.getExpenseDate() == null || e.getExpenseDate().isAfter(today.plusDays(1))) {
                out.add(f.classification(Classification.CORRUPTED).reason("The date is missing or in the future.").build());
                continue;
            }
            if (e.getAmount().signum() < 0 && e.getRefundOfExpenseId() == null) {
                out.add(f.classification(Classification.CORRUPTED).reason("A negative expense that is not linked to the purchase it refunds.").build());
                continue;
            }
            if (e.getRefundOfExpenseId() != null && !byId.containsKey(e.getRefundOfExpenseId())) {
                out.add(f.classification(Classification.MISSING).reason("A refund whose original purchase is no longer recorded.").build());
                continue;
            }
            if (e.getSourceFingerprint() != null && conflicted.contains(e.getSourceFingerprint())) {
                out.add(f.classification(Classification.CONFLICT).reason("A later email quoted the same payment reference with different details.").build());
                continue;
            }
            if (e.getSourceEmailId() != null) {
                String key = e.getSourceEmailId() + "|" + e.getAmount().stripTrailingZeros().toPlainString() + "|" + party;
                Expense first = firstFromEmail.get(key);
                Boolean over = lines.overBooked(e.getSourceEmailId());
                if (first != null && !Objects.equals(first.getExpenseDate(), e.getExpenseDate()) && !Boolean.FALSE.equals(over)) {
                    out.add(over == null
                        ? f.classification(Classification.REQUIRES_RECONCILIATION).duplicateOf(first.getId())
                            .reason("The same email has this payment on " + first.getExpenseDate() + " too (expense " + first.getId()
                                + ") — two statement lines, or one booked twice?").build()
                        : f.classification(Classification.DUPLICATE).duplicateOf(first.getId())
                            .reason("The same email's payment was booked again on a later day (first on "
                                + first.getExpenseDate() + ", expense " + first.getId() + "), and the email has fewer lines than rows.").build());
                    continue;
                }
                firstFromEmail.putIfAbsent(key, e);
            }
            String dayKey = e.getExpenseDate() + "|" + e.getAmount().stripTrailingZeros().toPlainString() + "|" + party;
            Expense twin = party.isEmpty() ? null : firstOnDay.get(dayKey);
            if (twin != null && !Objects.equals(twin.getSourceEmailId(), e.getSourceEmailId())) {
                out.add(f.classification(Classification.REQUIRES_RECONCILIATION).duplicateOf(twin.getId())
                    .reason("Same amount, day and payee as expense " + twin.getId() + " from another source — the same payment, or two?").build());
                continue;
            }
            firstOnDay.putIfAbsent(dayKey, e);
            if (e.getSourceFingerprint() != null && needsReview.contains(e.getSourceFingerprint())) {
                out.add(f.classification(Classification.REQUIRES_RECONCILIATION).reason("Imported while resembling another recorded transaction.").build());
                continue;
            }
            out.add(f.classification(Classification.VERIFIED).reason(e.getSourceEmailId() != null ? "From email " + e.getSourceEmailId() : "Entered by hand").build());
        }
        return out;
    }

    // ── Incomes ──

    static List<Finding> auditIncomes(List<Income> rows, Set<String> conflicted, Set<String> needsReview, EmailLines lines, LocalDate today) {
        List<Finding> out = new ArrayList<>();
        Map<String, Income> firstFromEmail = new HashMap<>();
        List<Income> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(Income::getIncomeDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Income::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        for (Income i : ordered) {
            Finding.FindingBuilder f = Finding.builder().entity(Entity.INCOME).id(i.getId()).amount(i.getAmount())
                .date(i.getIncomeDate()).label(i.getDescription());
            if (i.getAmount() == null || i.getAmount().signum() < 0) {
                out.add(f.classification(Classification.CORRUPTED).reason("No amount, or a negative one.").build());
                continue;
            }
            if (i.getIncomeDate() == null || i.getIncomeDate().isAfter(today.plusDays(1))) {
                out.add(f.classification(Classification.CORRUPTED).reason("The date is missing or in the future.").build());
                continue;
            }
            if (i.getSourceFingerprint() != null && conflicted.contains(i.getSourceFingerprint())) {
                out.add(f.classification(Classification.CONFLICT).reason("A later email quoted the same payment reference with different details.").build());
                continue;
            }
            if (i.getSourceEmailId() != null) {
                String payer = com.marketai.common.ledger.PartyNames.normalise(i.getPayer() != null ? i.getPayer() : i.getDescription());
                String key = i.getSourceEmailId() + "|" + i.getAmount().stripTrailingZeros().toPlainString() + "|" + i.getSource() + "|" + payer;
                Income first = firstFromEmail.get(key);
                Boolean over = lines.overBooked(i.getSourceEmailId());
                if (first != null && !Objects.equals(first.getIncomeDate(), i.getIncomeDate()) && !Boolean.FALSE.equals(over)) {
                    out.add(over == null
                        ? f.classification(Classification.REQUIRES_RECONCILIATION).duplicateOf(first.getId())
                            .reason("The same email has this credit on " + first.getIncomeDate() + " too (income " + first.getId()
                                + ") — two statement lines, or one booked twice?").build()
                        : f.classification(Classification.DUPLICATE).duplicateOf(first.getId())
                            .reason("The same email's credit was booked again on a later day (first on "
                                + first.getIncomeDate() + ", income " + first.getId() + "), and the email has fewer lines than rows.").build());
                    continue;
                }
                firstFromEmail.putIfAbsent(key, i);
            }
            if (i.getSource() == IncomeSource.UNMATCHED_SALE) {
                out.add(f.classification(Classification.REQUIRES_RECONCILIATION)
                    .reason("Sale proceeds with no matching holding, so no cost basis — add the purchase history.").build());
                continue;
            }
            if (i.getSource() == IncomeSource.CAPITAL_GAIN) {
                out.add(f.classification(Classification.REQUIRES_RECONCILIATION)
                    .reason("An older capital-gain entry; gains now come from the sale records, and this row does not keep a loss's sign.").build());
                continue;
            }
            if (i.getSourceFingerprint() != null && needsReview.contains(i.getSourceFingerprint())) {
                out.add(f.classification(Classification.REQUIRES_RECONCILIATION).reason("Imported while resembling another recorded transaction.").build());
                continue;
            }
            out.add(f.classification(Classification.VERIFIED).reason(i.getSourceEmailId() != null ? "From email " + i.getSourceEmailId() : "Entered by hand or derived").build());
        }
        return out;
    }

    // ── Holdings and their ledgers ──

    static List<Finding> auditHolding(Holding h, List<Transaction> txns, Set<String> conflicted, LocalDate today) {
        List<Finding> out = new ArrayList<>();
        Map<String, Transaction> firstBySignature = new HashMap<>();
        Set<Long> unmatchedSales = new HashSet<>();
        FifoLedger.Result fifo = FifoLedger.replay(txns.stream()
            .filter(t -> t.getTransactionDate() != null).map(FifoLedger.Trade::of).toList());
        if (fifo.unmatchedUnits().signum() > 0) {
            // Which sales ran past the purchases: replay in order and find the ones that did.
            BigDecimal held = BigDecimal.ZERO;
            for (Transaction t : txns) {
                if (t.getQuantity() == null) continue;
                if (t.getType() == Transaction.TransactionType.SPLIT) {
                    BigDecimal m = t.splitMultiplier();
                    if (m != null) held = held.multiply(m);
                } else if (t.addsUnits()) {
                    held = held.add(t.getQuantity());
                } else {
                    if (t.getQuantity().compareTo(held) > 0) unmatchedSales.add(t.getId());
                    held = held.subtract(t.getQuantity()).max(BigDecimal.ZERO);
                }
            }
        }
        for (Transaction t : txns) {
            Finding.FindingBuilder f = Finding.builder().entity(Entity.TRANSACTION).id(t.getId())
                .amount(t.getTotalAmount()).date(t.getTransactionDate())
                .label(h.getSymbol() + " " + (t.getType() != null ? t.getType().name() : "?"));
            boolean trade = t.getType() == Transaction.TransactionType.BUY || t.getType() == Transaction.TransactionType.SELL;
            if (t.getTransactionDate() == null || t.getTransactionDate().isAfter(today.plusDays(1))) {
                out.add(f.classification(Classification.CORRUPTED).reason("The trade date is missing or in the future.").build());
                continue;
            }
            if (trade && (t.getQuantity() == null || t.getQuantity().signum() <= 0 || t.getPrice() == null || t.getPrice().signum() <= 0)) {
                out.add(f.classification(Classification.CORRUPTED).reason("A trade with no units or no price.").build());
                continue;
            }
            if (t.getType() == Transaction.TransactionType.SPLIT && t.splitMultiplier() == null) {
                out.add(f.classification(Classification.CORRUPTED).reason("A split whose ratio can't be read; it is ignored in every calculation.").build());
                continue;
            }
            String fp = t.getProvenance() != null ? t.getProvenance().getSourceFingerprint() : null;
            if (fp != null && conflicted.contains(fp)) {
                out.add(f.classification(Classification.CONFLICT).reason("A later document quoted the same reference with different details.").build());
                continue;
            }
            if (trade) {
                String ref = t.getProvenance() != null ? t.getProvenance().getSourceReference() : null;
                String email = t.getProvenance() != null ? t.getProvenance().getSourceEmailId() : null;
                String sig = t.getType() + "|" + t.getTransactionDate() + "|" + t.getQuantity().stripTrailingZeros().toPlainString()
                    + "|" + t.getPrice().stripTrailingZeros().toPlainString();
                Transaction first = firstBySignature.get(sig);
                if (first != null) {
                    String firstRef = first.getProvenance() != null ? first.getProvenance().getSourceReference() : null;
                    String firstEmail = first.getProvenance() != null ? first.getProvenance().getSourceEmailId() : null;
                    boolean differentRefs = ref != null && firstRef != null && !ref.equalsIgnoreCase(firstRef);
                    if (!differentRefs) {
                        // Only a shared broker/RTA reference proves one trade. Two emails with the
                        // same day, units and price can still be two purchases (a SIP and a top-up).
                        boolean twoDocuments = email != null && firstEmail != null && !email.equals(firstEmail);
                        if (ref != null && ref.equalsIgnoreCase(firstRef)) {
                            out.add(f.classification(Classification.DUPLICATE).duplicateOf(first.getId())
                                .reason("The same trade reference as transaction " + first.getId() + ", read again.").build());
                        } else if (twoDocuments) {
                            out.add(f.classification(Classification.REQUIRES_RECONCILIATION).duplicateOf(first.getId())
                                .reason("Identical to transaction " + first.getId() + " (same day, units and price) but from another email — "
                                    + "the same purchase confirmed twice, or two purchases?").build());
                        } else {
                            out.add(f.classification(Classification.REQUIRES_RECONCILIATION).duplicateOf(first.getId())
                                .reason("Identical to transaction " + first.getId() + " (same day, units and price) — two fills, or one entered twice?").build());
                        }
                        continue;
                    }
                }
                firstBySignature.putIfAbsent(sig, t);
            }
            if (unmatchedSales.contains(t.getId())) {
                out.add(f.classification(Classification.MISSING).reason("Sells more units than the recorded purchases hold — a purchase is missing.").build());
                continue;
            }
            out.add(f.classification(Classification.VERIFIED).reason(t.getProvenance() != null && t.getProvenance().getSourceEmailId() != null
                ? "From email " + t.getProvenance().getSourceEmailId() : "Recorded in the ledger").build());
        }

        Finding.FindingBuilder hf = Finding.builder().entity(Entity.HOLDING).id(h.getId()).label(h.getSymbol())
            .amount(h.getQuantity() != null && h.getAverageCost() != null ? h.getQuantity().multiply(h.getAverageCost()) : null);
        if (txns.isEmpty()) {
            boolean open = h.getQuantity() != null && h.getQuantity().signum() > 0;
            out.add(open
                ? hf.classification(Classification.MISSING).reason("Units are held but no purchase is recorded, so cost, gains and XIRR rest on the holding's own figures.").build()
                : hf.classification(Classification.VERIFIED).reason("Closed, no history").build());
            return out;
        }
        PortfolioService.LedgerPosition pos = PortfolioService.replayPosition(txns);
        if (!PortfolioService.sameFigure(h.getQuantity(), pos.quantity()) || !PortfolioService.sameFigure(h.getAverageCost(), pos.averageCost())) {
            out.add(hf.classification(Classification.CORRUPTED).reason("Stored as " + plain(h.getQuantity()) + " units but the ledger gives "
                + plain(pos.quantity()) + (PortfolioService.sameFigure(h.getQuantity(), pos.quantity()) ? ", with a different average cost" : "")
                + ". A rebuild re-derives it from the ledger.").build());
        } else {
            out.add(hf.classification(Classification.VERIFIED).reason("Matches its ledger").build());
        }
        return out;
    }

    // ── Deposits ──

    static List<Finding> auditFds(List<FixedDeposit> fds, LocalDate today) {
        List<Finding> out = new ArrayList<>();
        for (FixedDeposit d : fds) {
            Finding.FindingBuilder f = Finding.builder().entity(Entity.FIXED_DEPOSIT).id(d.getId())
                .amount(d.getPrincipal()).date(d.getStartDate()).label(d.getBank() + " FD");
            String status = d.getStatus() == null ? "ACTIVE" : d.getStatus().toUpperCase(Locale.ROOT);
            if (d.getPrincipal() == null || d.getPrincipal().signum() <= 0 || d.getRate() == null || d.getRate().signum() <= 0) {
                out.add(f.classification(Classification.CORRUPTED).reason("No usable principal or rate.").build());
            } else if (d.getStartDate() != null && d.getMaturityDate() != null && d.getMaturityDate().isBefore(d.getStartDate())) {
                out.add(f.classification(Classification.CORRUPTED).reason("Matures before it starts.").build());
            } else if ("CLOSED".equals(status) && (d.getClosedDate() == null || d.getMaturityAmount() == null)) {
                out.add(f.classification(Classification.MISSING).reason("Closed, but the payout amount or date isn't recorded.").build());
            } else if (!"CLOSED".equals(status) && !"MATURED_RENEWED".equals(status) && d.getMaturityDate() != null
                    && d.getMaturityDate().plusDays(LAPSED_DEPOSIT_DAYS).isBefore(today)) {
                out.add(f.classification(Classification.REQUIRES_RECONCILIATION)
                    .reason("Matured on " + d.getMaturityDate() + " and nothing is recorded since — closed, renewed or still counted as open?").build());
            } else {
                out.add(f.classification(Classification.VERIFIED).reason("Consistent").build());
            }
        }
        return out;
    }

    static List<Finding> auditRds(List<RecurringDeposit> rds, LocalDate today) {
        List<Finding> out = new ArrayList<>();
        for (RecurringDeposit d : rds) {
            Finding.FindingBuilder f = Finding.builder().entity(Entity.RECURRING_DEPOSIT).id(d.getId())
                .amount(d.getMonthlyAmount()).date(d.getStartDate()).label(d.getBank() + " RD");
            String status = d.getStatus() == null ? "ACTIVE" : d.getStatus().toUpperCase(Locale.ROOT);
            LocalDate maturity = d.getStartDate() != null ? d.getStartDate().plusMonths(d.getTenureMonths()) : null;
            if (d.getMonthlyAmount() == null || d.getMonthlyAmount().signum() <= 0 || d.getTenureMonths() <= 0) {
                out.add(f.classification(Classification.CORRUPTED).reason("No usable instalment or tenure.").build());
            } else if ("CLOSED".equals(status) && (d.getClosedDate() == null || d.getMaturityAmount() == null)) {
                out.add(f.classification(Classification.MISSING).reason("Closed, but the payout amount or date isn't recorded.").build());
            } else if (!"CLOSED".equals(status) && !"MATURED_RENEWED".equals(status) && maturity != null
                    && maturity.plusDays(LAPSED_DEPOSIT_DAYS).isBefore(today)) {
                out.add(f.classification(Classification.REQUIRES_RECONCILIATION)
                    .reason("Matured on " + maturity + " and nothing is recorded since — closed, renewed or still counted as open?").build());
            } else {
                out.add(f.classification(Classification.VERIFIED).reason("Consistent").build());
            }
        }
        return out;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
