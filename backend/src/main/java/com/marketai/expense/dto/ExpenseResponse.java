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
    private String sourceEmailId;
    private String note;
    private LocalDateTime createdAt;
}
