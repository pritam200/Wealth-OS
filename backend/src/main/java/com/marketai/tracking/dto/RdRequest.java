package com.marketai.tracking.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RdRequest {
    @NotBlank private String bank;
    @NotNull @DecimalMin("1") private BigDecimal monthlyAmount;
    @NotNull @DecimalMin("0.01") @DecimalMax("30") private BigDecimal rate;
    private LocalDate startDate;
    @Min(1) @Max(360) private int tenureMonths = 12;
}
