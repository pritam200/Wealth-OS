package com.marketai.tracking.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.math.BigDecimal;

@Data
public class LoanRequest {
    @NotBlank private String name;
    private String type = "Other";
    @NotNull @DecimalMin("1") private BigDecimal emi;
    private BigDecimal outstanding = BigDecimal.ZERO;
    private BigDecimal rate = BigDecimal.ZERO;
    @Min(0) private int remainingMonths = 0;
}
