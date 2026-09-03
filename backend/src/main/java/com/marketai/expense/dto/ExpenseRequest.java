package com.marketai.expense.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ExpenseRequest {
    private String description;
    private BigDecimal amount;
    private String category;
    private LocalDate expenseDate;
    private String note;
}
