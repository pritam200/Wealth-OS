package com.marketai.redemption.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The amount is added straight into {@code MfRedemption.reinvestedAmount}, which drives
 * "awaiting redeployment" and the COMPLETED transition — so an unchecked null or negative value
 * corrupts how much of a redemption the user is told is still uninvested.
 */
@Data
public class ReinvestmentRequest {

    @NotNull(message = "A reinvestment needs an amount")
    @Positive(message = "A reinvestment amount must be greater than zero")
    private BigDecimal amount;

    private LocalDate date;

    @Size(max = 200)
    private String targetFund;

    @Size(max = 500)
    private String note;
}
