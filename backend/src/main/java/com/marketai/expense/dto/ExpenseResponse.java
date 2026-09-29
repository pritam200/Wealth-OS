package com.marketai.expense.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class ExpenseResponse {
    private Long id;
    private Long userId;
    private String description;
    private BigDecimal amount;
    private String category;
    private LocalDate expenseDate;
    private String merchant;
    private String paymentMethod;
    private Long cashAccountId;
    private String sourceEmailId;
    /** True when nothing was added because this transaction was already recorded; the existing
     *  record is returned. */
    private boolean alreadyRecorded;
    private String note;
    private String planCategoryOverride;
    private LocalDateTime createdAt;
}
