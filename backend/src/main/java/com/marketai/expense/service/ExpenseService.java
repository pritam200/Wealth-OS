package com.marketai.expense.service;

import com.marketai.expense.dto.ExpenseRequest;
import com.marketai.expense.dto.ExpenseResponse;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final com.marketai.card.repository.CardStatementLineRepository cardLines;
    private final MerchantCategoryRuleService merchantRules;

    @Transactional
    public ExpenseResponse addExpense(Long userId, ExpenseRequest req) {
        LocalDate date = req.getExpenseDate() != null ? req.getExpenseDate() : LocalDate.now();
        // A merchant the user typed themselves is authoritative — only fall back to the
        // email-import-style auto-derivation when they didn't name one, so a hand-typed
        // "SWIGGY*BLR" and an imported "Swiggy" still group together in analytics when the
        // user doesn't override it.
        String merchant = req.getMerchant() != null && !req.getMerchant().isBlank()
                ? req.getMerchant()
                : com.marketai.gmail.parser.SpendCategorizer.extractMerchant(req.getDescription());

        Expense duplicate = req.isConfirmSeparate() ? null
                : findRecordedFromEmail(userId, req.getAmount(), date, req.getDescription(), merchant);
        if (duplicate != null) {
            // Already imported from the bank's or merchant's email. Adding it again would double
            // the spend, so the existing record is returned and flagged; the user can still add
            // it with confirmSeparate if it really is a second payment.
            ExpenseResponse existing = toResponse(duplicate);
            existing.setAlreadyRecorded(true);
            return existing;
        }

        Expense expense = Expense.builder()
                .userId(userId)
                .description(req.getDescription())
                .amount(req.getAmount())
                .category(categoryFor(userId, req.getCategory(), merchant))
                .expenseDate(date)
                .merchant(merchant)
                .paymentMethod(req.getPaymentMethod())
                .cashAccountId(req.getCashAccountId())
                .note(req.getNote())
                .build();
        return toResponse(expenseRepository.save(expense));
    }

    /**
     * An email-imported expense this manual entry describes: same amount, same day, and the same
     * counterparty (normalised, so "SWIGGY*BLR" and "Swiggy" match). Only rows that came from an
     * email are considered — two hand-entered ₹250 coffees on one day are the user's own record
     * of two payments, and are both kept.
     */
    private Expense findRecordedFromEmail(Long userId, BigDecimal amount, LocalDate date,
                                          String description, String merchant) {
        if (amount == null || date == null) return null;
        String[] mine = {merchant, description};
        for (Expense e : expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, date, date)) {
            if (e.getSourceEmailId() == null) continue;
            if (e.getAmount() == null || e.getAmount().compareTo(amount) != 0) continue;
            if (com.marketai.common.ledger.PartyNames.anySame(mine, new String[]{e.getMerchant(), e.getDescription()})) return e;
        }
        return null;
    }

    public List<ExpenseResponse> listExpenses(Long userId) {
        LocalDate from = LocalDate.now().minusDays(90);
        LocalDate to = LocalDate.now();
        return expenseRepository
                .findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, from, to)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<ExpenseResponse> getMonthly(Long userId, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        return expenseRepository
                .findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, from, to)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ExpenseResponse updateExpense(Long userId, Long id, ExpenseRequest req) {
        Expense e = expenseRepository.findById(id).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
        if (req.getDescription() != null) e.setDescription(req.getDescription());
        // A refund is stored negative so it reduces the month's spend; the form edits its size.
        boolean refund = e.getRefundOfExpenseId() != null || (e.getAmount() != null && e.getAmount().signum() < 0);
        if (req.getAmount() != null) e.setAmount(refund ? req.getAmount().abs().negate() : req.getAmount());
        ExpenseCategory chosen = req.getCategory() != null ? ExpenseCategory.fromLabel(req.getCategory()) : null;
        if (chosen != null) e.setCategory(chosen);
        if (req.getExpenseDate() != null) e.setExpenseDate(req.getExpenseDate());
        // Sent blank to clear: a merchant that was wrong can be removed, not only replaced.
        if (req.getMerchant() != null) e.setMerchant(req.getMerchant().isBlank() ? null : req.getMerchant().trim());
        e.setPaymentMethod(req.getPaymentMethod());
        e.setCashAccountId(req.getCashAccountId());
        e.setNote(req.getNote());
        ExpenseResponse saved = toResponse(expenseRepository.save(e));
        if (req.isRememberForMerchant() && chosen != null && e.getMerchant() != null) {
            saved.setAlsoRecategorised(merchantRules.remember(userId, e.getMerchant(), chosen));
        }
        return saved;
    }

    /** A category the user typed wins; left blank, the merchant's remembered category is used. */
    private ExpenseCategory categoryFor(Long userId, String label, String merchant) {
        ExpenseCategory c = ExpenseCategory.fromLabel(label);
        if (c != ExpenseCategory.UNCATEGORIZED) return c;
        return merchantRules.categoryFor(userId, merchant).orElse(c);
    }

    /** Expenses in a date range (default: the last 90 days), optionally of one category or matching text. */
    public List<ExpenseResponse> search(Long userId, LocalDate from, LocalDate to, String category, String q) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(90);
        if (start.isAfter(end)) throw new IllegalArgumentException("The start date is after the end date");
        ExpenseCategory cat = category != null && !category.isBlank() ? ExpenseCategory.fromLabel(category) : null;
        String text = q != null && !q.isBlank() ? q.trim().toLowerCase(java.util.Locale.ROOT) : null;
        return expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, start, end).stream()
                .filter(e -> cat == null || e.getCategory() == cat)
                .filter(e -> text == null || contains(e.getMerchant(), text) || contains(e.getDescription(), text)
                        || contains(e.getPaymentMethod(), text) || contains(e.getNote(), text))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private static boolean contains(String s, String lowerNeedle) {
        return s != null && s.toLowerCase(java.util.Locale.ROOT).contains(lowerNeedle);
    }

    /**
     * The planner's "move to a different section" action. Sets the override key that
     * {@code PlannerService} reads instead of the live classifier result for this expense —
     * never creates a second row, so the household's total actual spend is unchanged; only which
     * category box the amount appears under moves. Passing null clears the override, reverting
     * this expense back to whatever the classifier currently says.
     */
    @Transactional
    public ExpenseResponse setPlanCategoryOverride(Long userId, Long id, String planCategoryKey) {
        Expense e = expenseRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
        e.setPlanCategoryOverride(planCategoryKey == null || planCategoryKey.isBlank() ? null : planCategoryKey);
        return toResponse(expenseRepository.save(e));
    }

    /**
     * Removes the expense with the refunds booked against it, which otherwise went on reducing
     * the month's spend for a purchase no longer there, and frees any card-statement line it
     * ticked off so the statement tally shows that line as missing again.
     */
    @Transactional
    public void deleteExpense(Long userId, Long id) {
        if (!expenseRepository.existsByIdAndUserId(id, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found");
        }
        List<Expense> refunds = expenseRepository.findByRefundOfExpenseId(id).stream()
                .filter(r -> userId.equals(r.getUserId())).toList();
        for (Expense gone : concat(refunds, expenseRepository.findById(id).orElseThrow())) {
            for (var line : cardLines.findByUserIdAndMatchedExpenseId(userId, gone.getId())) {
                line.setMatchedExpenseId(null);
                line.setStatus(com.marketai.card.entity.CardStatementLine.Status.MISSING);
                cardLines.save(line);
            }
        }
        expenseRepository.deleteAll(refunds);
        expenseRepository.deleteById(id);
    }

    private static List<Expense> concat(List<Expense> a, Expense b) {
        List<Expense> all = new java.util.ArrayList<>(a);
        all.add(b);
        return all;
    }

    // Stray Expense rows imported before BankTransactionParser started refusing to book
    // Investment-flavoured debits — these should never have existed and don't represent
    // real spending, so surface them for one-time cleanup rather than leaving them mixed
    // into the Expenses table indefinitely.
    public List<ExpenseResponse> listMiscategorizedInvestments(Long userId) {
        return expenseRepository.findByUserIdAndCategory(userId, ExpenseCategory.INVESTMENT)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public int purgeMiscategorizedInvestments(Long userId) {
        List<Expense> stray = expenseRepository.findByUserIdAndCategory(userId, ExpenseCategory.INVESTMENT);
        expenseRepository.deleteAll(stray);
        return stray.size();
    }

    public Map<String, Object> getMonthlySummary(Long userId, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());

        BigDecimal total = expenseRepository.sumByUserIdAndDateRange(userId, from, to);
        if (total == null) {
            total = BigDecimal.ZERO;
        }

        List<Object[]> rows = expenseRepository.sumByCategory(userId, from, to);
        Map<String, BigDecimal> byCategory = new HashMap<>();
        for (Object[] row : rows) {
            byCategory.put(((ExpenseCategory) row[0]).getLabel(), (BigDecimal) row[1]);
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total", total);
        summary.put("byCategory", byCategory);
        BigDecimal transfers = expenseRepository.sumTransfers(userId, from, to);
        summary.put("transfers", transfers != null ? transfers : BigDecimal.ZERO);
        return summary;
    }

    private ExpenseResponse toResponse(Expense e) {
        return ExpenseResponse.builder()
                .id(e.getId())
                .userId(e.getUserId())
                .description(e.getDescription())
                .amount(e.getAmount())
                .category(e.getCategory() != null ? e.getCategory().getLabel() : null)
                .expenseDate(e.getExpenseDate())
                .merchant(e.getMerchant())
                .paymentMethod(e.getPaymentMethod())
                .cashAccountId(e.getCashAccountId())
                .sourceEmailId(e.getSourceEmailId())
                .note(e.getNote())
                .planCategoryOverride(e.getPlanCategoryOverride())
                .refundOfExpenseId(e.getRefundOfExpenseId())
                .spending(e.getCategory() == null || e.getCategory().isSpending())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
