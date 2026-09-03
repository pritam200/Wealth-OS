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
        Expense expense = Expense.builder()
                .userId(userId)
                .description(req.getDescription())
                .amount(req.getAmount())
                .category(ExpenseCategory.fromLabel(req.getCategory()))
                .expenseDate(req.getExpenseDate() != null ? req.getExpenseDate() : LocalDate.now())
                .note(req.getNote())
                .build();
        return toResponse(expenseRepository.save(expense));
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
