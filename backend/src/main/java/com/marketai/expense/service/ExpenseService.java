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

    @Transactional
    public ExpenseResponse addExpense(Long userId, ExpenseRequest req) {
        LocalDate date = req.getExpenseDate() != null ? req.getExpenseDate() : LocalDate.now();
        // Normalise the merchant the same way the email-import path does, so a hand-typed
        // "SWIGGY*BLR" and an imported "Swiggy" group together in analytics instead of
        // splitting one merchant's spend across two labels.
        String merchant = com.marketai.gmail.parser.SpendCategorizer.extractMerchant(req.getDescription());

        Expense duplicate = findExistingDuplicate(userId, req.getAmount(), date, req.getDescription(), merchant);
        if (duplicate != null) {
            // Same transaction already recorded (typically already imported from the bank's
            // email alert). Return the existing row rather than silently creating a second one —
            // the user's spend total must not double just because they also entered it by hand.
            return toResponse(duplicate);
        }

        Expense expense = Expense.builder()
                .userId(userId)
                .description(req.getDescription())
                .amount(req.getAmount())
                .category(ExpenseCategory.fromLabel(req.getCategory()))
                .expenseDate(date)
                .merchant(merchant)
                .note(req.getNote())
                .build();
        return toResponse(expenseRepository.save(expense));
    }

    /**
     * Cross-source duplicate check: same amount, same date, and a matching merchant or
     * description already on file. Deliberately same-day + exact-amount only — a looser window
     * would start suppressing genuine repeat spends (two coffees, same price, same shop), which
     * would understate real expenses.
     */
    private Expense findExistingDuplicate(Long userId, BigDecimal amount, LocalDate date,
                                          String description, String merchant) {
        if (amount == null || date == null) return null;
        for (Expense e : expenseRepository.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(userId, date, date)) {
            if (e.getAmount() == null || e.getAmount().compareTo(amount) != 0) continue;
            if (merchant != null && merchant.equalsIgnoreCase(e.getMerchant())) return e;
            if (description != null && description.equalsIgnoreCase(e.getDescription())) return e;
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
        if (req.getAmount() != null) e.setAmount(req.getAmount());
        if (req.getCategory() != null) e.setCategory(ExpenseCategory.fromLabel(req.getCategory()));
        if (req.getExpenseDate() != null) e.setExpenseDate(req.getExpenseDate());
        e.setNote(req.getNote());
        return toResponse(expenseRepository.save(e));
    }

    @Transactional
    public void deleteExpense(Long userId, Long id) {
        if (!expenseRepository.existsByIdAndUserId(id, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found");
        }
        expenseRepository.deleteById(id);
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
                .sourceEmailId(e.getSourceEmailId())
                .note(e.getNote())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
