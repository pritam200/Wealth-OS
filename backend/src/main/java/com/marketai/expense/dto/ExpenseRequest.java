package com.marketai.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Constraints mirror the {@code expenses} column definitions, so a bad request is a 400 that
 * names the field rather than a 500 from a not-null or value-too-long violation surfacing as
 * "An unexpected error occurred". {@code @Positive} on the amount is the substantive one: a
 * negative expense used to persist and then reduce reported spend and the planner's totals.
 */
@Data
public class ExpenseRequest {

    @NotBlank(message = "An expense needs a description")
    @Size(max = 200, message = "Description must be 200 characters or fewer")
    private String description;

    @NotNull(message = "An expense needs an amount")
    @Positive(message = "An expense amount must be greater than zero")
    private BigDecimal amount;

    @Size(max = 60)
    private String category;

    private LocalDate expenseDate;

    @Size(max = 500)
    private String note;
}
