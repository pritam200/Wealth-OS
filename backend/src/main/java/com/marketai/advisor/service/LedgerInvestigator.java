package com.marketai.advisor.service;

import com.marketai.advisor.dto.AdvisorEvidence;
import com.marketai.common.ledger.Provenance;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.gmail.entity.ProcessedEmail;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import com.marketai.income.entity.Income;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.investmentplan.dto.MonthlyPlanReviewResponse;
import com.marketai.investmentplan.dto.PlannedInvestmentResponse;
import com.marketai.investmentplan.service.PlannedInvestmentService;
import com.marketai.mf.entity.MfNavHistory;
import com.marketai.mf.repository.MfNavHistoryRepository;
import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.service.NetWorthService;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.reconciliation.entity.ReconciliationIssueRecord;
import com.marketai.reconciliation.service.DataAuditService;
import com.marketai.reconciliation.service.DataAuditService.Classification;
import com.marketai.reconciliation.service.DataAuditService.Finding;
import com.marketai.reconciliation.service.ReconciliationIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The investigation questions behind the advisor chat ("why did my net worth change", "where did
 * my plan go", "find duplicates"). Each answer is computed here from the ledger, the data audit
 * and the reconciliation records, and carries the records it rests on. The model only chose the
 * question; it never sees or writes a figure.
 */
@Component
@RequiredArgsConstructor
public class LedgerInvestigator {

    /** How many records an answer lists; the count in the text is always the full count. */
    static final int EVIDENCE_LIMIT = 50;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy");

    private final NetWorthService netWorthService;
    private final PortfolioContextService portfolioContextService;
    private final IncomeRepository incomeRepo;
    private final ExpenseRepository expenseRepo;
    private final TransactionRepository transactionRepo;
    private final PlannedInvestmentService planService;
    private final MfNavHistoryRepository navRepo;
    private final DataAuditService dataAudit;
    private final ReconciliationIssueService issueService;
    private final ProcessedEmailRepository processedEmailRepo;

    public record Answer(String text, Map<String, Object> data, List<AdvisorEvidence> evidence) {}

    /** Which holdings a question is about. */
    public enum Scope { MF, STOCK, ALL }

    // ── Net worth ────────────────────────────────────────────────────────────

    public Answer netWorthChange(Long userId, LocalDate today) {
        LocalDate monthStart = today.withDayOfMonth(1);
        List<NetWorthSnapshot> series = netWorthService.series(userId);
        // The last snapshot before the month began; failing that, the first one this month.
        NetWorthSnapshot base = null;
        for (NetWorthSnapshot s : series) {
            if (s.getNetWorth() != null && s.getSnapshotDate().isBefore(monthStart)) base = s;
        }
        if (base == null) {
            base = series.stream()
                .filter(s -> s.getNetWorth() != null && !s.getSnapshotDate().isBefore(monthStart) && s.getSnapshotDate().isBefore(today))
                .findFirst().orElse(null);
        }
        PortfolioContext now = portfolioContextService.build(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("netWorthToday", now.getNetWorth());
        data.put("dataQuality", now.getDataQuality());
        if (base == null) {
            return new Answer("There is no earlier net-worth snapshot this month to compare with, so the change can't "
                + "be measured yet. Your net worth today is ₹" + plain(now.getNetWorth())
                + ". A snapshot is now recorded every night." + gapNote(now), data, List.of());
        }

        LocalDate from = base.getSnapshotDate().plusDays(1);
        BigDecimal change = nz(now.getNetWorth()).subtract(base.getNetWorth());
        BigDecimal income = income(userId, from, today);
        BigDecimal spending = spending(userId, from, today);
        BigDecimal bought = BigDecimal.ZERO, sold = BigDecimal.ZERO;
        for (Transaction t : transactionRepo.findForUserBetween(userId, from, today)) {
            if (t.getType() == Transaction.TransactionType.BUY) bought = bought.add(t.getTotalAmount());
            else if (t.getType() == Transaction.TransactionType.SELL) sold = sold.add(t.getTotalAmount());
        }
        BigDecimal saved = income.subtract(spending);
        BigDecimal other = change.subtract(saved);

        data.put("baselineDate", base.getSnapshotDate());
        data.put("baselineNetWorth", base.getNetWorth());
        data.put("change", change);
        data.put("assetsChange", nz(now.getTotalAssets()).subtract(nz(base.getTotalAssets())));
        data.put("liabilitiesChange", nz(now.getTotalLiabilities()).subtract(nz(base.getTotalLiabilities())));
        data.put("income", income);
        data.put("spending", spending);
        data.put("notIncomeOrSpending", other);
        data.put("investmentsBought", bought);
        data.put("investmentsSold", sold);

        StringBuilder sb = new StringBuilder();
        sb.append("Your net worth went from ₹").append(plain(base.getNetWorth())).append(" on ").append(base.getSnapshotDate())
          .append(" to ₹").append(plain(now.getNetWorth())).append(" today, ")
          .append(change.signum() >= 0 ? "up" : "down").append(" ₹").append(plain(change.abs())).append(". ");
        sb.append("Since then the ledger records ₹").append(plain(income)).append(" of income and ₹").append(plain(spending))
          .append(" of spending, a net ").append(saved.signum() >= 0 ? "saving" : "outflow").append(" of ₹").append(plain(saved.abs())).append(". ");
        sb.append("The other ").append(other.signum() >= 0 ? "+" : "−").append("₹").append(plain(other.abs()))
          .append(" is not income or spending: it is the change in the value of holdings and deposits, and any money the ledger doesn't record.");
        if (bought.signum() > 0 || sold.signum() > 0) {
            sb.append(" You bought investments for ₹").append(plain(bought)).append(" and sold for ₹").append(plain(sold))
              .append("; that moves money between cash and investments and doesn't change net worth by itself.");
        }
        sb.append(gapNote(now));
        return new Answer(sb.toString(), data, List.of());
    }

    // ── Investment plan ──────────────────────────────────────────────────────

    public Answer investmentPlan(Long userId, LocalDate today) {
        LocalDate month = today.withDayOfMonth(1);
        MonthlyPlanReviewResponse r = planService.getReview(userId, month);
        List<PlannedInvestmentResponse> lines = new ArrayList<>();
        lines.addAll(r.getPending());
        lines.addAll(r.getCompleted());
        lines.addAll(r.getOverInvested());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", month);
        data.put("totalPlanned", r.getTotalPlanned());
        data.put("totalFunded", r.getTotalFunded());
        data.put("totalInvested", r.getTotalInvested());
        data.put("totalAwaitingInvestment", r.getTotalAwaitingInvestment());
        data.put("totalPending", r.getTotalPending());
        if (lines.isEmpty()) {
            return new Answer("There is no investment plan for " + month.format(MONTH) + ".", data, List.of());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(month.format(MONTH)).append(": ₹").append(plain(r.getTotalPlanned())).append(" planned. ₹")
          .append(plain(r.getTotalFunded())).append(" has been transferred and ₹").append(plain(r.getTotalInvested()))
          .append(" invested");
        if (nz(r.getTotalAwaitingInvestment()).signum() > 0) {
            sb.append("; ₹").append(plain(r.getTotalAwaitingInvestment())).append(" was transferred but is not invested yet");
        }
        sb.append(". ₹").append(plain(r.getTotalPending())).append(" of the plan is still to be invested.");
        List<PlannedInvestmentResponse> open = r.getPending();
        if (!open.isEmpty()) {
            sb.append(" Still pending: ").append(open.stream().limit(5)
                .map(l -> label(l) + " (₹" + plain(l.getRemainingAmount()) + " left, " + stageText(l.getStage()) + ")")
                .collect(Collectors.joining("; "))).append(".");
        }
        List<AdvisorEvidence> evidence = lines.stream().map(l -> AdvisorEvidence.builder()
            .label(label(l) + " — " + stageText(l.getStage()) + ": ₹" + plain(l.getFundedAmount()) + " transferred, ₹"
                + plain(l.getInvestedAmount()) + " invested")
            .amount(l.getPlannedAmount()).date(l.getDueDate()).source("Monthly investment plan").build()).toList();
        return new Answer(sb.toString(), data, evidence);
    }

    private static String label(PlannedInvestmentResponse l) {
        String type = l.getInvestmentType() == null ? "Investment" : l.getInvestmentType().replace('_', ' ');
        return l.getDestinationRef() == null || l.getDestinationRef().isBlank() ? type : l.getDestinationRef() + " (" + type + ")";
    }

    static String stageText(String stage) {
        if (stage == null) return "not started";
        return switch (stage) {
            case "FUNDED" -> "transferred, not all invested";
            case "INVESTED" -> "invested, below plan";
            case "SETTLED" -> "done";
            default -> "not started";
        };
    }

    // ── Imported records ─────────────────────────────────────────────────────

    public Answer importedTransactions(Long userId, String subject, LocalDate today) {
        LocalDate from = today.withDayOfMonth(1);
        List<Expense> expenses = expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, from, today)
            .stream().filter(e -> e.getSourceEmailId() != null).toList();
        List<Income> incomes = incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, from, today)
            .stream().filter(i -> i.getSourceEmailId() != null).toList();
        List<Transaction> trades = transactionRepo.findForUserBetween(userId, from, today).stream()
            .filter(t -> t.getProvenance() != null && t.getProvenance().getSourceEmailId() != null).toList();

        Set<String> ids = new HashSet<>();
        expenses.forEach(e -> ids.add(e.getSourceEmailId()));
        incomes.forEach(i -> ids.add(i.getSourceEmailId()));
        trades.forEach(t -> ids.add(t.getProvenance().getSourceEmailId()));
        Map<String, ProcessedEmail> emails = emails(userId, ids);

        List<AdvisorEvidence> evidence = new ArrayList<>();
        int expenseCount = 0, incomeCount = 0, tradeCount = 0;
        BigDecimal expenseSum = BigDecimal.ZERO, incomeSum = BigDecimal.ZERO, tradeSum = BigDecimal.ZERO;
        for (Expense e : expenses) {
            ProcessedEmail m = emails.get(e.getSourceEmailId());
            if (!matches(subject, sender(m), subject(m), e.getMerchant(), e.getDescription(), e.getPaymentMethod())) continue;
            expenseCount++;
            expenseSum = expenseSum.add(nz(e.getAmount()));
            evidence.add(ev("expense", e.getId(), e.getExpenseDate(), "Spent: " + firstNonBlank(e.getMerchant(), e.getDescription()), e.getAmount(), m));
        }
        for (Income i : incomes) {
            ProcessedEmail m = emails.get(i.getSourceEmailId());
            if (!matches(subject, sender(m), subject(m), i.getPayer(), i.getDescription())) continue;
            incomeCount++;
            incomeSum = incomeSum.add(nz(i.getAmount()));
            evidence.add(ev("income", i.getId(), i.getIncomeDate(), "Received: " + firstNonBlank(i.getPayer(), i.getDescription()), i.getAmount(), m));
        }
        for (Transaction t : trades) {
            ProcessedEmail m = emails.get(t.getProvenance().getSourceEmailId());
            Holding h = t.getHolding();
            if (!matches(subject, sender(m), subject(m), h.getName(), h.getSymbol(), h.getBroker())) continue;
            tradeCount++;
            tradeSum = tradeSum.add(t.getTotalAmount());
            evidence.add(ev("transaction", t.getId(), t.getTransactionDate(), tradeLabel(t), t.getTotalAmount(), m));
        }
        evidence.sort(Comparator.comparing(AdvisorEvidence::getDate, Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from);
        data.put("to", today);
        data.put("filter", subject);
        data.put("expenses", Map.of("count", expenseCount, "total", expenseSum));
        data.put("incomes", Map.of("count", incomeCount, "total", incomeSum));
        data.put("trades", Map.of("count", tradeCount, "total", tradeSum));

        int total = expenseCount + incomeCount + tradeCount;
        String which = subject == null ? "from email" : "from emails matching “" + subject + "”";
        if (total == 0) {
            return new Answer("Nothing has been imported " + which + " since " + from + ".", data, List.of());
        }
        List<String> parts = new ArrayList<>();
        if (expenseCount > 0) parts.add(expenseCount + " spending record" + plural(expenseCount) + " (₹" + plain(expenseSum) + ")");
        if (incomeCount > 0) parts.add(incomeCount + " income record" + plural(incomeCount) + " (₹" + plain(incomeSum) + ")");
        if (tradeCount > 0) parts.add(tradeCount + " trade" + plural(tradeCount) + " (₹" + plain(tradeSum) + ")");
        String text = total + " record" + plural(total) + " imported " + which + " since " + from + ": "
            + String.join(", ", parts) + "." + moreNote(evidence.size());
        return new Answer(text, data, limit(evidence));
    }

    // ── Fund value change ────────────────────────────────────────────────────

    public Answer valueChange(Long userId, String subject, Scope scope) {
        if (scope == Scope.STOCK) {
            return new Answer("Share prices aren't stored day by day, so a stock's value can only be compared for "
                + "funds, which have a NAV history. Ask about a fund instead.", Map.of(), List.of());
        }
        List<Holding> funds = holdings(userId, subject, Scope.MF);
        if (funds.isEmpty()) {
            return new Answer(subject == null ? "You have no open fund holdings." : "No open fund holding matches “" + subject + "”.",
                Map.of(), List.of());
        }
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<AdvisorEvidence> evidence = new ArrayList<>();
        for (Holding h : funds) {
            String name = firstNonBlank(h.getName(), h.getSymbol());
            if (h.getAmfiSchemeCode() == null) {
                lines.add(name + ": not linked to an AMFI scheme, so there is no NAV history to compare.");
                continue;
            }
            List<MfNavHistory> navs = navRepo.findTop2BySchemeCodeOrderByDateDesc(h.getAmfiSchemeCode());
            if (navs.size() < 2) {
                lines.add(name + ": only " + navs.size() + " NAV stored so far, nothing to compare with.");
                continue;
            }
            FundMove m = fundMove(h.getQuantity(), navs.get(1), navs.get(0),
                transactionRepo.findByHoldingIdOrderByTransactionDateAscIdAsc(h.getId()));
            StringBuilder sb = new StringBuilder(name).append(": ₹").append(plain(m.valueBefore())).append(" on ").append(m.before().getDate())
                .append(" → ₹").append(plain(m.valueAfter())).append(" on ").append(m.after().getDate())
                .append(" (").append(signed(m.valueAfter().subtract(m.valueBefore()))).append("). NAV ₹")
                .append(m.before().getNav().stripTrailingZeros().toPlainString()).append(" → ₹")
                .append(m.after().getNav().stripTrailingZeros().toPlainString()).append(" moved it ").append(signed(m.navEffect()));
            if (m.unitsTraded().signum() != 0) {
                sb.append("; units bought or sold since then account for ").append(signed(m.tradeEffect()));
            }
            sb.append(".");
            if (h.getCurrentPrice() != null && h.getCurrentPrice().compareTo(m.after().getNav()) != 0) {
                sb.append(" The app still values it at the ").append(h.getPriceAsOf() == null ? "last" : h.getPriceAsOf().toString())
                  .append(" price of ₹").append(h.getCurrentPrice().stripTrailingZeros().toPlainString())
                  .append(" until the nightly NAV refresh runs.");
            }
            lines.add(sb.toString());
            rows.add(Map.of("holding", name, "navBefore", m.before().getNav(), "navDateBefore", m.before().getDate(),
                "navAfter", m.after().getNav(), "navDateAfter", m.after().getDate(),
                "valueBefore", m.valueBefore(), "valueAfter", m.valueAfter(),
                "navEffect", m.navEffect(), "tradeEffect", m.tradeEffect()));
            Map<String, ProcessedEmail> sources = emails(userId, m.tradesSince().stream().map(Transaction::getProvenance)
                .filter(p -> p != null && p.getSourceEmailId() != null).map(Provenance::getSourceEmailId).collect(Collectors.toSet()));
            for (Transaction t : m.tradesSince()) {
                Provenance p = t.getProvenance();
                evidence.add(AdvisorEvidence.builder().kind("transaction").id(t.getId()).date(t.getTransactionDate())
                    .label(tradeLabel(t)).amount(t.getTotalAmount())
                    .source(provenanceText(p, p == null ? null : sources.get(p.getSourceEmailId()))).build());
            }
        }
        String text = String.join(" ", lines) + (rows.isEmpty() ? "" : " NAVs are the fund's published values, which appear a day after the date they are for.");
        return new Answer(text, Map.of("funds", rows), limit(evidence));
    }

    /** What moved a fund between two NAVs: the NAV itself, and units traded after the earlier one. */
    record FundMove(MfNavHistory before, MfNavHistory after, BigDecimal unitsTraded,
                    BigDecimal valueBefore, BigDecimal valueAfter, BigDecimal navEffect, BigDecimal tradeEffect,
                    List<Transaction> tradesSince) {}

    static FundMove fundMove(BigDecimal unitsNow, MfNavHistory before, MfNavHistory after, List<Transaction> txns) {
        BigDecimal traded = BigDecimal.ZERO;
        List<Transaction> since = new ArrayList<>();
        for (Transaction t : txns) {
            if (t.getTransactionDate() == null || !t.getTransactionDate().isAfter(before.getDate()) || t.getQuantity() == null) continue;
            if (t.addsUnits()) traded = traded.add(t.getQuantity());
            else if (t.getType() == Transaction.TransactionType.SELL) traded = traded.subtract(t.getQuantity());
            else continue;
            since.add(t);
        }
        BigDecimal units = nz(unitsNow);
        BigDecimal unitsBefore = units.subtract(traded);
        BigDecimal valueBefore = unitsBefore.multiply(before.getNav()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal valueAfter = units.multiply(after.getNav()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal navEffect = unitsBefore.multiply(after.getNav().subtract(before.getNav())).setScale(2, RoundingMode.HALF_UP);
        // The remainder, so the two parts always add up to the change shown.
        BigDecimal tradeEffect = valueAfter.subtract(valueBefore).subtract(navEffect);
        return new FundMove(before, after, traded, valueBefore, valueAfter, navEffect, tradeEffect, since);
    }

    // ── Data audit ───────────────────────────────────────────────────────────

    public Answer duplicates(Long userId) {
        DataAuditService.Report report = dataAudit.audit(userId);
        List<Finding> dups = report.getFindings().stream()
            .filter(f -> f.getClassification() == Classification.DUPLICATE).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordsChecked", report.getTotalRecords());
        data.put("duplicates", dups.size());
        data.put("spendingOverstatedBy", report.getDuplicateExpenseAmount());
        data.put("incomeOverstatedBy", report.getDuplicateIncomeAmount());
        if (dups.isEmpty()) {
            return new Answer("No duplicates found among the " + report.getTotalRecords() + " records checked.", data, List.of());
        }
        StringBuilder sb = new StringBuilder().append(dups.size()).append(" record").append(plural(dups.size()))
            .append(" duplicate").append(dups.size() == 1 ? "s" : "").append(" another already recorded");
        if (nz(report.getDuplicateExpenseAmount()).signum() > 0) sb.append("; spending is overstated by ₹").append(plain(report.getDuplicateExpenseAmount()));
        if (nz(report.getDuplicateIncomeAmount()).signum() > 0) sb.append("; income is overstated by ₹").append(plain(report.getDuplicateIncomeAmount()));
        sb.append(". They can be removed from the Reconciliation Center after taking a backup.").append(moreNote(dups.size()));
        return new Answer(sb.toString(), data, limit(dups.stream().map(LedgerInvestigator::findingEvidence).toList()));
    }

    public Answer missingTransactions(Long userId) {
        DataAuditService.Report report = dataAudit.audit(userId);
        List<Finding> gaps = report.getFindings().stream()
            .filter(f -> f.getClassification() == Classification.MISSING
                || f.getClassification() == Classification.REQUIRES_RECONCILIATION
                || f.getClassification() == Classification.CONFLICT)
            .toList();
        // A deposit the audit already lists is not listed a second time from the maturity check.
        Set<String> listed = gaps.stream().map(f -> f.getEntity() + ":" + f.getId()).collect(Collectors.toSet());
        List<ReconciliationIssueRecord> issues = issueService.current(userId).stream()
            .filter(i -> !listed.contains(depositEntity(i.getDomain()) + ":" + i.getReferenceId())).toList();
        long failedEmails = processedEmailRepo.countByUserIdAndStatus(userId, "FAILED");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("auditFindings", gaps.size());
        data.put("openIssues", issues.size());
        data.put("failedEmails", failedEmails);
        if (gaps.isEmpty() && issues.isEmpty() && failedEmails == 0) {
            return new Answer("Nothing looks missing: the data audit, the reconciliation checks and the email import "
                + "log report no gaps.", data, List.of());
        }
        List<String> parts = new ArrayList<>();
        long missing = gaps.stream().filter(f -> f.getClassification() == Classification.MISSING).count();
        int look = gaps.size() - (int) missing;
        if (missing > 0) parts.add(missing + " record" + plural((int) missing) + (missing == 1 ? " implies" : " imply") + " another that isn't recorded");
        if (look > 0) parts.add(look + " record" + plural(look) + (look == 1 ? " needs" : " need") + " a look");
        if (!issues.isEmpty()) parts.add(issues.size() + " open reconciliation issue" + plural(issues.size()));
        if (failedEmails > 0) parts.add(failedEmails + " email" + plural((int) failedEmails) + " could not be imported");
        StringBuilder sb = new StringBuilder("Possible gaps: ").append(String.join("; ", parts)).append(".");
        gaps.stream().limit(3).forEach(f -> sb.append(" ").append(f.getLabel()).append(" — ").append(f.getReason()));
        issues.stream().limit(3).forEach(i -> sb.append(" ").append(i.getDescription()));
        if (failedEmails > 0) sb.append(" The failed emails are listed in the Reconciliation Center.");

        List<AdvisorEvidence> evidence = new ArrayList<>(gaps.stream().map(LedgerInvestigator::findingEvidence).toList());
        issues.forEach(i -> evidence.add(AdvisorEvidence.builder().label(i.getDescription())
            .date(i.getFirstSeenAt() == null ? null : i.getFirstSeenAt().toLocalDate())
            .source("Reconciliation check: " + i.getType()).build()));
        return new Answer(sb.toString(), data, limit(evidence));
    }

    private static String depositEntity(String domain) {
        return "FD".equals(domain) ? DataAuditService.Entity.FIXED_DEPOSIT.name()
            : "RD".equals(domain) ? DataAuditService.Entity.RECURRING_DEPOSIT.name() : "none";
    }

    static AdvisorEvidence findingEvidence(Finding f) {
        return AdvisorEvidence.builder().kind(kindOf(f.getEntity())).id(f.getId()).date(f.getDate())
            .label(f.getLabel() + " — " + f.getReason()).amount(f.getAmount())
            .source("Data audit: " + f.getClassification().name().replace('_', ' ').toLowerCase()).build();
    }

    static String kindOf(DataAuditService.Entity e) {
        return switch (e) {
            case EXPENSE -> "expense";
            case INCOME -> "income";
            case TRANSACTION -> "transaction";
            case FIXED_DEPOSIT -> "fd";
            case RECURRING_DEPOSIT -> "rd";
            case HOLDING -> null;
        };
    }

    // ── Sources behind holdings ──────────────────────────────────────────────

    public Answer holdingSources(Long userId, String subject, Scope scope) {
        List<Holding> holdings = holdings(userId, subject, scope);
        String what = scope == Scope.MF ? "fund" : scope == Scope.STOCK ? "stock" : "";
        if (holdings.isEmpty()) {
            return new Answer("No open " + (what.isEmpty() ? "" : what + " ") + "holding"
                + (subject == null ? "s." : " matches “" + subject + "”."), Map.of(), List.of());
        }
        Map<Long, Holding> byId = holdings.stream().collect(Collectors.toMap(Holding::getId, Function.identity()));
        List<Transaction> txns = transactionRepo.findByHoldingIdInOrderByTransactionDateAscIdAsc(new ArrayList<>(byId.keySet()));
        Set<String> ids = txns.stream().map(Transaction::getProvenance)
            .filter(p -> p != null && p.getSourceEmailId() != null).map(Provenance::getSourceEmailId).collect(Collectors.toSet());
        Map<String, ProcessedEmail> emails = emails(userId, ids);

        int fromEmail = 0, manual = 0, unknown = 0;
        Map<Long, Integer> tradesPerHolding = new HashMap<>();
        List<AdvisorEvidence> evidence = new ArrayList<>();
        for (Transaction t : txns) {
            Provenance p = t.getProvenance();
            if (p != null && p.getSourceEmailId() != null) fromEmail++;
            else if (p != null && Provenance.MANUAL.equals(p.getExtractionMethod())) manual++;
            else unknown++;
            tradesPerHolding.merge(t.getHolding().getId(), 1, Integer::sum);
            evidence.add(AdvisorEvidence.builder().kind("transaction").id(t.getId()).date(t.getTransactionDate())
                .label(tradeLabel(t)).amount(t.getTotalAmount())
                .source(provenanceText(p, p == null ? null : emails.get(p.getSourceEmailId()))).build());
        }
        List<String> noTrades = holdings.stream().filter(h -> !tradesPerHolding.containsKey(h.getId()))
            .map(h -> firstNonBlank(h.getName(), h.getSymbol())).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("holdings", holdings.size());
        data.put("trades", txns.size());
        data.put("fromEmail", fromEmail);
        data.put("sourceEmails", ids.size());
        data.put("enteredByHand", manual);
        data.put("noSourceRecorded", unknown);
        data.put("holdingsWithNoTrades", noTrades);

        StringBuilder sb = new StringBuilder().append(holdings.size()).append(" ").append(what.isEmpty() ? "" : what + " ")
            .append("holding").append(plural(holdings.size())).append(holdings.size() == 1 ? " rests on " : " rest on ").append(txns.size()).append(" trade")
            .append(plural(txns.size())).append(": ").append(fromEmail).append(" read from ").append(ids.size())
            .append(" email").append(plural(ids.size())).append(", ").append(manual).append(" entered by hand, ")
            .append(unknown).append(" with no source recorded.");
        if (!noTrades.isEmpty()) {
            sb.append(" No trades at all behind: ").append(String.join(", ", noTrades.stream().limit(5).toList()))
              .append(noTrades.size() > 5 ? " and " + (noTrades.size() - 5) + " more" : "")
              .append(" — their units are the only record, with no document behind them.");
        }
        sb.append(moreNote(evidence.size()));
        return new Answer(sb.toString(), data, limit(evidence));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Holding> holdings(Long userId, String subject, Scope scope) {
        return portfolioContextService.getAllHoldings(userId).stream()
            .filter(h -> scope == Scope.ALL || (scope == Scope.MF) == isFund(h))
            .filter(h -> matches(subject, h.getName(), h.getSymbol(), h.getBroker(), h.getFolio()))
            .toList();
    }

    static boolean isFund(Holding h) {
        return h.getSymbol() != null && h.getSymbol().toUpperCase().endsWith(".MF");
    }

    private BigDecimal income(Long userId, LocalDate from, LocalDate to) {
        // Sale proceeds with no cost basis, and old capital-gain rows, are not income: the sale
        // moved money out of an investment, which the net-worth change already reflects.
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : incomeRepo.sumBySource(userId, from, to)) {
            if (row[0] == com.marketai.income.entity.IncomeSource.UNMATCHED_SALE
                || row[0] == com.marketai.income.entity.IncomeSource.CAPITAL_GAIN || row[1] == null) continue;
            total = total.add((BigDecimal) row[1]);
        }
        return total;
    }

    private BigDecimal spending(Long userId, LocalDate from, LocalDate to) {
        // Money put into investments is not spending; own-account transfers are already excluded.
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : expenseRepo.sumByCategory(userId, from, to)) {
            if (row[0] == ExpenseCategory.INVESTMENT || row[1] == null) continue;
            total = total.add((BigDecimal) row[1]);
        }
        return total;
    }

    private Map<String, ProcessedEmail> emails(Long userId, Set<String> ids) {
        // A HashMap, not Map.of(): callers look up null ids for hand-entered rows.
        Map<String, ProcessedEmail> byId = new HashMap<>();
        if (ids.isEmpty()) return byId;
        processedEmailRepo.findByUserIdAndGmailMessageIdIn(userId, ids).forEach(m -> byId.putIfAbsent(m.getGmailMessageId(), m));
        return byId;
    }

    /**
     * Case-insensitive: is every word of the subject in one of the fields? "HDFC Mid Cap" matches a
     * fund named "HDFC Mid Cap Fund - Direct Growth"; no subject matches everything.
     */
    static boolean matches(String subject, String... fields) {
        if (subject == null) return true;
        for (String word : subject.toLowerCase(Locale.ROOT).split(" ")) {
            if (word.isBlank()) continue;
            boolean found = false;
            for (String f : fields) {
                if (f != null && f.toLowerCase(Locale.ROOT).contains(word)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    private static AdvisorEvidence ev(String kind, Long id, LocalDate date, String label, BigDecimal amount, ProcessedEmail m) {
        return AdvisorEvidence.builder().kind(kind).id(id).date(date).label(label).amount(amount)
            .source(m == null ? "Email (not in the import log)" : "Email from " + m.getSender()).build();
    }

    static String provenanceText(Provenance p, ProcessedEmail m) {
        if (p != null && p.getSourceEmailId() != null) return m != null && m.getSender() != null ? "Email from " + m.getSender() : "Email";
        if (p != null && Provenance.MANUAL.equals(p.getExtractionMethod())) return "Entered by hand";
        return "No source recorded";
    }

    private static String tradeLabel(Transaction t) {
        Holding h = t.getHolding();
        String name = h == null ? "" : firstNonBlank(h.getName(), h.getSymbol());
        String qty = t.getQuantity() == null ? "" : " " + t.getQuantity().stripTrailingZeros().toPlainString() + " units";
        return t.getType().name().charAt(0) + t.getType().name().substring(1).toLowerCase() + qty + " " + name;
    }

    private static String sender(ProcessedEmail m) { return m == null ? null : m.getSender(); }
    private static String subject(ProcessedEmail m) { return m == null ? null : m.getSubject(); }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b != null ? b : "";
    }

    private static List<AdvisorEvidence> limit(List<AdvisorEvidence> all) {
        return all.size() <= EVIDENCE_LIMIT ? all : all.subList(0, EVIDENCE_LIMIT);
    }

    private static String moreNote(int total) {
        return total > EVIDENCE_LIMIT ? " The first " + EVIDENCE_LIMIT + " are listed." : "";
    }

    /** The first data gap that bears on the net-worth figure, as a trailing note. */
    static String gapNote(PortfolioContext ctx) {
        if (ctx.getDataGaps() == null) return "";
        // The sector-coverage gaps are about the concentration analysis, not the total.
        return ctx.getDataGaps().stream().filter(g -> !g.startsWith("Sector"))
            .findFirst().map(g -> " Note: " + g).orElse("");
    }

    private static String plural(int n) { return n == 1 ? "" : "s"; }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    static String plain(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String signed(BigDecimal v) {
        return (v.signum() < 0 ? "−₹" : "+₹") + plain(v.abs());
    }
}
