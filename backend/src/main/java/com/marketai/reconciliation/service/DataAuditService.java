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
 *   <li><b>MISREAD</b> — booked from a misread source: a broker's funds ledger or a payment
 *       into an investment taken as spending or income, a transfer between the holder's own
 *       accounts, an option premium taken as a share price, a split the prices don't show.
 *       Removing it loses nothing real.</li>
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
@lombok.extern.slf4j.Slf4j
public class DataAuditService {

    public enum Classification { VERIFIED, DUPLICATE, MISREAD, CONFLICT, CORRUPTED, MISSING, REQUIRES_RECONCILIATION }

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
    private final com.marketai.auth.repository.UserRepository userRepo;
    private final com.marketai.market.repository.PriceHistoryRepository priceHistoryRepo;
    private final com.marketai.reconciliation.check.DepositoryHoldingMismatchCheck depositoryCheck;

    /**
     * A holding that matches its own trades can still disagree with the depository: the trades
     * are then consistent but incomplete (a contract note never arrived) or wrong (an F&O trade
     * read as shares). Such a holding is not VERIFIED; it needs reconciling against the demat
     * statement, whose figures the reason gives.
     */
    private void markDepositoryDifferences(Long userId, List<Finding> all) {
        Map<Long, String> differs = new HashMap<>();
        try {
            for (var issue : depositoryCheck.run(userId)) {
                if (issue.getReferenceId() != null) differs.putIfAbsent(issue.getReferenceId(), issue.getDescription());
            }
        } catch (RuntimeException e) {
            log.warn("Depository comparison skipped in the data audit: {}", e.toString());
            return;
        }
        if (differs.isEmpty()) return;
        for (int i = 0; i < all.size(); i++) {
            Finding f = all.get(i);
            String why = f.getEntity() == Entity.HOLDING ? differs.get(f.getId()) : null;
            if (why != null && f.getClassification() == Classification.VERIFIED) {
                all.set(i, f.toBuilder().classification(Classification.REQUIRES_RECONCILIATION).reason(why).build());
            }
        }
    }

    @Data @Builder(toBuilder = true)
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
        /** A DUPLICATE or MISREAD a rebuild may remove; otherwise {@link #refusalReason} says why not. */
        private boolean removable;
        private String refusalReason;
    }

    /**
     * Why a rebuild would refuse to remove a duplicate or misread record, or null when it may. The
     * rebuild applies this same rule, so what the audit offers to remove is what it removes: a
     * share sale or split read wrongly writes only its ledger row (gains are derived from the
     * ledger), but a duplicated sale, or any fund sale, may also have written a redemption or tax
     * record that removing the ledger row alone would leave behind.
     */
    public static String removalRefusal(Entity entity, Classification c, Transaction t) {
        if (c != Classification.DUPLICATE && c != Classification.MISREAD) return "It is not a duplicate or misread";
        return switch (entity) {
            case EXPENSE, INCOME -> null;
            case TRANSACTION -> {
                boolean misread = c == Classification.MISREAD;
                boolean fund = t != null && t.getHolding() != null && t.getHolding().getSymbol() != null
                    && t.getHolding().getSymbol().endsWith(".MF");
                boolean ok = t != null && (t.getType() == Transaction.TransactionType.BUY
                    || misread && t.getType() == Transaction.TransactionType.SPLIT
                    || misread && t.getType() == Transaction.TransactionType.SELL && !fund);
                yield ok ? null : "A sale — remove it by hand with its redemption record";
            }
            default -> "A rebuild doesn't remove this kind of record";
        };
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
        Classification.CORRUPTED, Classification.CONFLICT, Classification.DUPLICATE, Classification.MISREAD,
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
        // Misreads first, and each is then left out of the other checks, so every record is
        // still counted once.
        String holder = userRepo.findById(userId).map(com.marketai.auth.entity.User::getName).orElse(null);
        Map<String, String> senders = senderDomains(userId, expenses, incomes);
        Set<Long> misreadExpenses = new HashSet<>(), misreadIncomes = new HashSet<>();
        for (Expense e : expenses) {
            misreadCash(e.getMerchant(), e.getDescription(), senders.get(e.getSourceEmailId()), holder, false, e.getSourceEmailId())
                .ifPresent(r -> { misreadExpenses.add(e.getId()); all.add(Finding.builder().entity(Entity.EXPENSE).id(e.getId())
                    .amount(e.getAmount()).date(e.getExpenseDate()).label(e.getMerchant() != null ? e.getMerchant() : e.getDescription())
                    .classification(Classification.MISREAD).reason(r).build()); });
        }
        for (Income i : incomes) {
            misreadCash(i.getPayer(), i.getDescription(), senders.get(i.getSourceEmailId()), holder, true, i.getSourceEmailId())
                .ifPresent(r -> { misreadIncomes.add(i.getId()); all.add(Finding.builder().entity(Entity.INCOME).id(i.getId())
                    .amount(i.getAmount()).date(i.getIncomeDate()).label(i.getPayer() != null ? i.getPayer() : i.getDescription())
                    .classification(Classification.MISREAD).reason(r).build()); });
        }
        all.addAll(auditExpenses(expenses.stream().filter(e -> !misreadExpenses.contains(e.getId())).toList(), conflicted, needsReview, lines, today));
        all.addAll(auditIncomes(incomes.stream().filter(i -> !misreadIncomes.contains(i.getId())).toList(), conflicted, needsReview, lines, today));
        Map<Long, Transaction> trades = new HashMap<>();
        for (Portfolio p : portfolioRepo.findByUserIdOrderByIdAsc(userId)) {
            for (Holding h : holdingRepo.findByPortfolioId(p.getId())) {
                List<Transaction> txns = transactionRepo.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId());
                txns.forEach(t -> trades.put(t.getId(), t));
                Map<Long, String> misread = misreadTrades(h, txns, closesFor(h, txns));
                for (Finding f : auditHolding(h, txns, conflicted, today)) {
                    String why = f.getEntity() == Entity.TRANSACTION ? misread.get(f.getId()) : null;
                    all.add(why == null ? f : f.toBuilder().classification(Classification.MISREAD).reason(why).duplicateOf(null).build());
                }
            }
        }
        all.addAll(auditFds(fdRepo.findByUserIdOrderByCreatedAtDesc(userId), today));
        all.addAll(auditRds(rdRepo.findByUserIdOrderByCreatedAtDesc(userId), today));
        markDepositoryDifferences(userId, all);
        for (Finding f : all) {
            if (f.getClassification() != Classification.DUPLICATE && f.getClassification() != Classification.MISREAD) continue;
            String refusal = removalRefusal(f.getEntity(), f.getClassification(), f.getEntity() == Entity.TRANSACTION ? trades.get(f.getId()) : null);
            f.setRemovable(refusal == null);
            f.setRefusalReason(refusal);
        }
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

    /** The sending domain of each email the cash rows came from. */
    private Map<String, String> senderDomains(Long userId, List<Expense> expenses, List<Income> incomes) {
        Set<String> ids = new HashSet<>();
        expenses.forEach(e -> { if (e.getSourceEmailId() != null) ids.add(e.getSourceEmailId()); });
        incomes.forEach(i -> { if (i.getSourceEmailId() != null) ids.add(i.getSourceEmailId()); });
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (var m : processedEmailRepo.findByUserIdAndGmailMessageIdIn(userId, ids)) {
            String d = com.marketai.document.classify.ClassificationCandidate.email(m.getSender(), null, null).senderDomain();
            if (d != null) out.put(m.getGmailMessageId(), d);
        }
        return out;
    }

    private static final java.util.regex.Pattern HONORIFIC = java.util.regex.Pattern.compile("^(mr|mrs|ms|miss|dr)\\.?\\s+", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern NAME_IN_BRACKETS = java.util.regex.Pattern.compile("\\(([^)]+)\\)");

    /**
     * Why an email-booked expense or income is not spending or income at all, or empty when it
     * may be. Hand entries are never second-guessed.
     */
    static Optional<String> misreadCash(String party, String description, String senderDomain, String holder,
                                        boolean income, String sourceEmailId) {
        if (sourceEmailId == null) return Optional.empty();
        Optional<String> broker = com.marketai.document.classify.IssuerDomainRegistry.brokerFor(senderDomain);
        if (broker.isPresent()) {
            return Optional.of("A line from " + broker.get() + "'s funds statement — money added to or paid out of the "
                + "trading account, not " + (income ? "income" : "spending") + ". The trades are booked from the contract notes.");
        }
        String text = ((party != null ? party : "") + " " + (description != null ? description : "")).trim();
        if (income && text.toLowerCase(Locale.ROOT).contains("opening balance")) {
            return Optional.of("A statement's opening balance read as money received.");
        }
        if (com.marketai.gmail.parser.SpendCategorizer.categorize(text) == com.marketai.expense.entity.ExpenseCategory.INVESTMENT) {
            return Optional.of(income
                ? "Sale or redemption proceeds paid out by a broker or fund house — the sale is booked from its contract note or confirmation, so it is not income."
                : "Money paid into an investment — the purchase is booked from its contract note or fund confirmation, so it is not spending.");
        }
        if (holder != null && party != null) {
            String name = party.trim();
            java.util.regex.Matcher m = NAME_IN_BRACKETS.matcher(name);
            if (m.find()) name = m.group(1).trim();
            name = HONORIFIC.matcher(name).replaceFirst("");
            if (com.marketai.gmail.parser.BankAlertParsers.isHolder(name, holder)) {
                return Optional.of("A transfer between your own accounts — not " + (income ? "income" : "spending") + ".");
            }
        }
        return Optional.empty();
    }

    /** A share trade priced under a quarter, or over four times, of the day's close is not a share price. */
    static final BigDecimal PRICE_BAND = new BigDecimal("4");

    /** Daily closes around each model-read trade in this holding, by date. */
    private NavigableMap<LocalDate, BigDecimal> closesFor(Holding h, List<Transaction> txns) {
        NavigableMap<LocalDate, BigDecimal> out = new TreeMap<>();
        if (h.getSymbol() == null || h.getSymbol().endsWith(".MF")) return out;
        LocalDate from = null, to = null;
        for (Transaction t : txns) {
            if (!modelRead(t) || t.getTransactionDate() == null) continue;
            from = from == null || t.getTransactionDate().isBefore(from) ? t.getTransactionDate() : from;
            to = to == null || t.getTransactionDate().isAfter(to) ? t.getTransactionDate() : to;
        }
        if (from == null) return out;
        String s = h.getSymbol().trim().toUpperCase(Locale.ROOT);
        String base = s.endsWith(".NS") ? s.substring(0, s.length() - 3) : s;
        for (String sym : new LinkedHashSet<>(List.of(s, base + ".NS", base))) {
            for (var p : priceHistoryRepo.findDailyBySymbolAndDateBetween(sym, from.minusDays(10), to.plusDays(30))) {
                if (p.getClose() != null && p.getClose().signum() > 0) out.putIfAbsent(p.getDate(), p.getClose());
            }
        }
        return out;
    }

    private static boolean modelRead(Transaction t) {
        String m = t.getProvenance() != null ? t.getProvenance().getExtractionMethod() : null;
        return m != null && m.endsWith("_LLM");
    }

    /**
     * Model-read trades the market prices contradict: a share trade at an option's premium
     * (Hero MotoCorp "bought" at ₹164 the day it closed at ₹5,020), or a split after which the
     * price did not fall (HAL "split 1:5" while trading at ₹4,549 before and ₹4,725 after).
     */
    static Map<Long, String> misreadTrades(Holding h, List<Transaction> txns, NavigableMap<LocalDate, BigDecimal> closes) {
        Map<Long, String> out = new HashMap<>();
        if (closes.isEmpty()) return out;
        for (Transaction t : txns) {
            if (!modelRead(t) || t.getTransactionDate() == null) continue;
            LocalDate d = t.getTransactionDate();
            boolean trade = t.getType() == Transaction.TransactionType.BUY || t.getType() == Transaction.TransactionType.SELL;
            if (trade && t.getPrice() != null && t.getPrice().signum() > 0) {
                BigDecimal close = closes.get(d);
                if (close == null) continue;
                if (t.getPrice().multiply(PRICE_BAND).compareTo(close) < 0 || t.getPrice().compareTo(close.multiply(PRICE_BAND)) > 0) {
                    out.put(t.getId(), "Priced at ₹" + plain(t.getPrice()) + " when " + h.getSymbol() + " closed at ₹" + plain(close)
                        + " that day — an option or futures price read as a share trade.");
                }
            } else if (t.getType() == Transaction.TransactionType.SPLIT && t.splitMultiplier() != null
                    && t.splitMultiplier().compareTo(BigDecimal.ONE) > 0) {
                var before = closes.lowerEntry(d);
                // The lowest close in the next 20 days, so an ex-date a few days after the record
                // date still shows the fall.
                var after = closes.subMap(d, false, d.plusDays(20), true).entrySet().stream()
                    .min(Map.Entry.comparingByValue()).orElse(null);
                if (before == null || after == null || before.getKey().isBefore(d.minusDays(10))) continue;
                // A real 1:5 split leaves the price near a fifth; it fell by less than half of that.
                BigDecimal fall = before.getValue().divide(after.getValue(), 4, java.math.RoundingMode.HALF_UP);
                BigDecimal expected = t.splitMultiplier();
                if (fall.compareTo(BigDecimal.ONE.add(expected.subtract(BigDecimal.ONE).divide(new BigDecimal("2"), 4, java.math.RoundingMode.HALF_UP))) < 0) {
                    out.put(t.getId(), h.getSymbol() + " closed at ₹" + plain(before.getValue()) + " before and no lower than ₹"
                        + plain(after.getValue()) + " in the 20 days after this split — a " + plain(t.getRatioFrom()) + ":" + plain(t.getRatioTo())
                        + " split would have cut the price to about 1/" + plain(expected) + ".");
                }
            }
        }
        return out;
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
