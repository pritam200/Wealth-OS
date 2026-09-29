package com.marketai.income.service;

import com.marketai.income.dto.IncomeRequest;
import com.marketai.income.dto.IncomeResponse;
import com.marketai.income.entity.Income;
import com.marketai.income.entity.IncomeSource;
import com.marketai.income.repository.IncomeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IncomeService {

    private final IncomeRepository repo;

    public IncomeResponse add(Long userId, IncomeRequest req) {
        LocalDate date = req.getIncomeDate() != null ? req.getIncomeDate() : LocalDate.now();
        if (!req.isConfirmSeparate()) {
            Income recorded = findRecordedFromEmail(userId, req.getAmount(), date, req.getDescription());
            if (recorded != null) {
                // Same reasoning as ExpenseService.addExpense: the credit already came in by email.
                IncomeResponse existing = toDto(recorded);
                existing.setAlreadyRecorded(true);
                return existing;
            }
        }
        Income i = Income.builder()
            .userId(userId)
            .description(req.getDescription())
            .amount(req.getAmount())
            .source(IncomeSource.fromLabel(req.getSource()))
            .incomeDate(date)
            .note(req.getNote())
            .build();
        return toDto(repo.save(i));
    }

    /** An email-imported credit of the same amount on the same day from the same payer. */
    private Income findRecordedFromEmail(Long userId, java.math.BigDecimal amount, LocalDate date, String description) {
        if (amount == null) return null;
        for (Income i : repo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, date, date)) {
            if (i.getSourceEmailId() == null) continue;
            if (i.getAmount() == null || i.getAmount().compareTo(amount) != 0) continue;
            if (com.marketai.common.ledger.PartyNames.anySame(new String[]{description},
                    new String[]{i.getDescription(), i.getPayer()})) return i;
        }
        return null;
    }

    public List<IncomeResponse> list(Long userId, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.withDayOfMonth(from.lengthOfMonth());
        return repo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(userId, from, to)
                   .stream().map(this::toDto).collect(Collectors.toList());
    }

    public List<IncomeResponse> listBySource(Long userId, String source, int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to   = LocalDate.of(year, 12, 31);
        return repo.findByUserIdAndSourceAndIncomeDateBetweenOrderByIncomeDateDesc(userId, IncomeSource.fromLabel(source), from, to)
                   .stream().map(this::toDto).collect(Collectors.toList());
    }

    public IncomeResponse update(Long userId, Long id, IncomeRequest req) {
        Income i = repo.findById(id).filter(x -> x.getUserId().equals(userId))
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        if (req.getDescription() != null) i.setDescription(req.getDescription());
        if (req.getAmount() != null) i.setAmount(req.getAmount());
        if (req.getSource() != null) i.setSource(IncomeSource.fromLabel(req.getSource()));
        if (req.getIncomeDate() != null) i.setIncomeDate(req.getIncomeDate());
        i.setNote(req.getNote());
        return toDto(repo.save(i));
    }

    public void delete(Long userId, Long id) {
        repo.findById(id).ifPresent(i -> {
            if (i.getUserId().equals(userId)) repo.delete(i);
        });
    }

    public Map<String, Object> getMonthlySummary(Long userId, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.withDayOfMonth(from.lengthOfMonth());
        // SUM over no rows is SQL NULL. A month with no income is ₹0, not "unknown" — and a null
        // total renders as a blank tile downstream. ExpenseService already coalesces the same way.
        BigDecimal total = repo.sumByUserIdAndDateRange(userId, from, to);
        if (total == null) total = BigDecimal.ZERO;
        List<Object[]> rows = repo.sumBySource(userId, from, to);
        Map<String, BigDecimal> bySource = new LinkedHashMap<>();
        for (Object[] r : rows) bySource.merge(IncomeSource.labelOf(r[0]), (BigDecimal) r[1], BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("bySource", bySource);
        return result;
    }

    private IncomeResponse toDto(Income i) {
        return IncomeResponse.builder()
            .id(i.getId()).description(i.getDescription()).amount(i.getAmount())
            .source(i.getSource() != null ? i.getSource().getLabel() : null).incomeDate(i.getIncomeDate())
            .payer(i.getPayer()).paymentMethod(i.getPaymentMethod())
            .sourceEmailId(i.getSourceEmailId())
            .note(i.getNote()).createdAt(i.getCreatedAt()).build();
    }
}
