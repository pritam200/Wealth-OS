package com.marketai.income.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * See {@link com.marketai.expense.dto.ExpenseRequest} — same reasoning. An income row with a
 * null amount broke every sum that reads it, and a negative one silently offset real income.
 */
@Data
public class IncomeRequest {

    @NotBlank(message = "An income entry needs a description")
    @Size(max = 200, message = "Description must be 200 characters or fewer")
    private String description;

    @NotNull(message = "An income entry needs an amount")
    @Positive(message = "An income amount must be greater than zero")
    private BigDecimal amount;

    @Size(max = 60)
    private String source;

    private LocalDate incomeDate;

    @Size(max = 500)
    private String note;
}
