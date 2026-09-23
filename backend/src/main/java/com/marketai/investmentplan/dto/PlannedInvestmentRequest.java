package com.marketai.investmentplan.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PlannedInvestmentRequest {

    private Long sourceAccountId;

    @NotNull(message = "A planned investment needs an amount")
    @Positive(message = "Planned amount must be greater than zero")
    private BigDecimal plannedAmount;

    @NotNull(message = "A planned investment needs a type")
    private String investmentType; // MUTUAL_FUND | STOCK | FD | RD | EPF | OTHER

    @Size(max = 200)
    private String destinationRef;

    private boolean scheduled;

    private LocalDate dueDate;
}
