package com.marketai.rent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RentScheduleRequest {

    @NotNull(message = "A rent schedule needs an amount")
    @Positive(message = "Rent amount must be greater than zero")
    private BigDecimal amount;

    @NotNull(message = "A rent schedule needs a due day of month")
    @Min(value = 1, message = "Due day must be between 1 and 28")
    @Max(value = 28, message = "Due day must be between 1 and 28")
    private Integer dueDayOfMonth;

    @Size(max = 200)
    private String paidTo;

    private Long cashAccountId;

    @Size(max = 100)
    private String paymentMethod;
}
