package com.marketai.portfolio.dto;

import javax.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class AddHoldingRequest {

    @NotBlank
    private String symbol;

    @NotBlank
    private String name;

    @NotNull
    @Positive
    private BigDecimal quantity;

    @NotNull
    @Positive
    private BigDecimal price;

    @NotNull
    private LocalDate transactionDate;

    @PositiveOrZero
    private BigDecimal charges = BigDecimal.ZERO;

    private String notes;

    private String broker;  // provider/AMC for MF, broker for stocks
    private String folio;   // MF folio number
}
