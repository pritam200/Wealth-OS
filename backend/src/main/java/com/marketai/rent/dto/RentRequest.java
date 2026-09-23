package com.marketai.rent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RentRequest {

    // Which month this payment is for; defaults to paidDate's month, then today's month.
    private LocalDate month;

    @NotNull(message = "A rent payment needs an amount")
    @Positive(message = "Rent amount must be greater than zero")
    private BigDecimal amount;

    private LocalDate paidDate;

    @Size(max = 200)
    private String paidTo;

    private Long cashAccountId;

    @Size(max = 100)
    private String paymentMethod;

    @Size(max = 100)
    private String referenceId;

    @Size(max = 500)
    private String note;
}
